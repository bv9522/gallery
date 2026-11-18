/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.database.Cursor
import android.util.Log
import com.google.ai.edge.gallery.customtasks.smartalbum.DefaultSemanticRetrievalServiceProvider
import com.google.ai.edge.gallery.customtasks.smartalbum.SmartAlbumIndexingWorker
import com.google.ai.edge.gallery.customtasks.smartalbum.SmartAlbumSource
import com.google.ai.edge.gallery.customtasks.smartalbum.SmartAlbumViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.services.photolibrary.DefaultPhotoLibraryService
import com.google.ai.edge.gallery.services.photolibrary.PhotoAsset
import com.google.ai.edge.gallery.services.photolibrary.PhotoLibraryService
import com.google.ai.edge.gallery.services.semanticretrieval.GemmaEmbeddingModelStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "AgentMediaLibrary"

/** A bounded, model-safe media result. Content URIs and filesystem paths stay inside Gallery. */
data class AgentMediaSearchItem(
  val handle: String,
  val displayName: String,
  val mimeType: String,
  val source: String,
  val dateTakenMs: Long,
  val sizeBytes: Long,
  val durationMs: Long?,
  val similarity: Float?,
)

data class AgentMediaSearchPage(
  val query: String,
  val returned: Int,
  val limit: Int,
  val truncated: Boolean,
  val visualSearchUsed: Boolean,
  val visualIndexAvailable: Boolean,
  val items: List<AgentMediaSearchItem>,
  val note: String? = null,
)

data class AgentMediaPlaybackReference(val uri: Uri, val displayName: String, val isVideo: Boolean)

private data class StoredMediaReference(
  val uri: Uri,
  val displayName: String,
  val isVideo: Boolean,
  val expiresAtMs: Long,
)

/**
 * Searches MediaStore and explicitly granted SAF trees. Semantic search reuses the existing
 * EmbeddingGemma-2 / Smart Album vector database and indexing worker; it does not create a second
 * embedding engine or scanner.
 */
class GalleryMediaLibraryOperations(
  context: Context,
  private val models: List<Model>,
  private val authorizer: NativeCapabilityAuthorizer,
  private val photoLibrary: PhotoLibraryService = DefaultPhotoLibraryService(context.applicationContext),
) {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver
  private val references = ConcurrentHashMap<String, StoredMediaReference>()

  suspend fun search(
    query: String,
    mediaKind: String = "ANY",
    mode: String = "AUTO",
    limit: Int = DEFAULT_RESULT_LIMIT,
  ): AgentMediaSearchPage {
    require(query.isNotBlank() && query.length <= MAX_QUERY_CHARS) {
      "Media search query must contain 1–$MAX_QUERY_CHARS characters."
    }
    require(mediaKind in MEDIA_KINDS) { "media_kind must be ANY, IMAGE, or VIDEO." }
    require(mode in SEARCH_MODES) { "search_mode must be AUTO, NAME, or VISUAL." }
    require(limit in 1..MAX_RESULT_LIMIT) { "Media search limit must be between 1 and $MAX_RESULT_LIMIT." }

    if (!authorizer.authorize(
        AndroidCapability.MEDIA_SCAN,
        "Search Gallery-readable photos and videos for '$query' (type: $mediaKind, mode: $mode, limit: $limit). This reads media names and, when available, searches the existing on-device visual index."
      )
    ) {
      throw SecurityException("Media search was not approved.")
    }

    references.clear()
    val mediaStoreSearch = if (mode == "VISUAL") {
      MediaStoreSearchResult(emptyList(), !hasMediaStoreReadAccess(), false)
    } else {
      searchMediaStoreByName(query, mediaKind, limit)
    }
    val mediaStoreMatches = mediaStoreSearch.assets
    val treeScan = if (mode == "VISUAL") {
      TreeScanResult(emptyList(), 0, false)
    } else {
      scanGrantedTrees(query, mediaKind, MAX_TREE_SEARCH_MATCHES)
    }
    val visualIndex = if (mode == "NAME") null else searchVisualIndex(query, mediaKind, limit)
    val combined = linkedMapOf<String, Pair<PhotoAsset, Float?>>()

    visualIndex?.results.orEmpty().forEach { result ->
      combined[result.first.contentUri.toString()] = result
    }
    (mediaStoreMatches + treeScan.assets).forEach { asset ->
      combined.putIfAbsent(asset.contentUri.toString(), asset to null)
    }

    val items = combined.values.take(limit).map { (asset, similarity) ->
      val handle = "media_" + UUID.randomUUID().toString().replace("-", "")
      references[handle] = StoredMediaReference(
        uri = asset.contentUri,
        displayName = safeDisplayName(asset.displayName),
        isVideo = asset.isVideo,
        expiresAtMs = System.currentTimeMillis() + HANDLE_TTL_MS,
      )
      AgentMediaSearchItem(
        handle = handle,
        displayName = safeDisplayName(asset.displayName),
        mimeType = if (asset.isVideo) "video/*" else "image/*",
        source = if (asset.contentUri.authority == "media") "MediaStore" else "User-granted storage",
        dateTakenMs = asset.dateTaken,
        sizeBytes = asset.sizeBytes.coerceAtLeast(0L),
        durationMs = asset.durationMs?.coerceIn(0L, MAX_DURATION_MS),
        similarity = similarity?.takeIf { it.isFinite() }?.coerceIn(-1f, 1f),
      )
    }

    pruneExpiredHandles()
    val visualUsed = visualIndex?.results?.isNotEmpty() == true
    val visualAvailable = visualIndex?.indexAvailable == true
    val note = when {
      mediaStoreSearch.permissionRequired -> "Android media permission is not granted; results include only files in persisted user-granted storage folders."
      mode == "NAME" -> "Filename search only."
      visualIndex == null || !visualIndex.indexAvailable ->
        "Visual index unavailable. Install EmbeddingGemma-2 and run media_index to build the local visual index."
      visualIndex.results.isEmpty() ->
        "Visual index is available, but no indexed visual matches were returned. Filename matches are included where found."
      else -> "Visual matches are ranked first; filename matches follow."
    }
    return AgentMediaSearchPage(
      query = query,
      returned = items.size,
      limit = limit,
      truncated = treeScan.truncated || mediaStoreSearch.truncated || combined.size > limit || (visualIndex?.results?.size == limit),
      visualSearchUsed = visualUsed,
      visualIndexAvailable = visualAvailable,
      items = items,
      note = note,
    )
  }

  suspend fun startIndexing(): String {
    val model = findDownloadedEmbeddingModel()
      ?: throw IllegalStateException(
        "EmbeddingGemma-2 is not downloaded for Smart Album. Download it from Gallery's model manager before building the visual media index."
      )
    val modelPath = model.getPath(appContext)
    if (!File(modelPath).isFile) {
      throw IllegalStateException("The EmbeddingGemma-2 model file is not available locally.")
    }
    val mediaStoreCount = if (hasMediaStoreReadAccess()) {
      runCatching { photoLibrary.fetchMediaStoreAssetCount() }.getOrDefault(0)
    } else {
      0
    }
    val grantedTreeCount = resolver.persistedUriPermissions.count { permission ->
      DocumentsContract.isTreeUri(permission.uri) && permission.isReadPermission
    }
    if (mediaStoreCount == 0 && grantedTreeCount == 0) {
      throw SecurityException("No readable MediaStore library or persisted storage-folder grant is available.")
    }
    if (!authorizer.authorize(
        AndroidCapability.MEDIA_SCAN,
        "Build or update Gallery's local visual media index using EmbeddingGemma-2. MediaStore currently exposes $mediaStoreCount photos/videos; Gallery also has $grantedTreeCount read-granted storage folders, from which up to $MAX_TREE_INDEX_ASSETS media files will be added. Indexing may use CPU/GPU resources and run as background work. No media is uploaded."
      )
    ) {
      throw SecurityException("Media indexing was not approved.")
    }

    val treeScan = scanGrantedTrees(query = null, mediaKind = "ANY", maxMatches = MAX_TREE_INDEX_ASSETS)
    if (treeScan.assets.isEmpty() && !hasMediaStoreReadAccess()) {
      throw SecurityException("Gallery has no Android photo/video permission and no media files were found in persisted storage grants. Open Smart Album to grant photo/video access or grant a storage folder through document_tree request_access.")
    }
    if (treeScan.assets.isNotEmpty()) {
      photoLibrary.addCustomAssets(treeScan.assets)
    }

    SmartAlbumIndexingWorker.enqueue(
      context = appContext,
      modelPath = modelPath,
      source = SmartAlbumSource.USER_PHOTOS,
      accelerator = GemmaEmbeddingModelStore.getDefaultAccelerator(model),
      maxInputSequenceLength = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
      visionTokenBudget = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
      visionAccelerator = model.backendSpec.visionAccelerator,
      audioAccelerator = model.backendSpec.audioAccelerator,
    )
    return buildString {
      append("Visual media indexing was queued using EmbeddingGemma-2. ")
      append("Gallery will reuse its existing Smart Album worker and vector database. ")
      append("User-granted storage files discovered: ${treeScan.assets.size}.")
      if (treeScan.truncated) append(" The storage scan reached its safety limit; some files were not added.")
      append(" Indexing runs in the background; search can use completed records as they become available.")
    }
  }

  fun resolvePlaybackHandle(handle: String): AgentMediaPlaybackReference? {
    if (!handle.matches(Regex("^media_[0-9a-f]{32}$"))) return null
    val stored = references.remove(handle) ?: return null
    if (stored.expiresAtMs <= System.currentTimeMillis()) return null
    return AgentMediaPlaybackReference(stored.uri, stored.displayName, stored.isVideo)
  }

  private suspend fun searchMediaStoreByName(
    query: String,
    mediaKind: String,
    limit: Int,
  ): MediaStoreSearchResult = withContext(Dispatchers.IO) {
    val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
    val types = when (mediaKind) {
      "VIDEO" -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
      "IMAGE" -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
      else -> listOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
      )
    }
    val typeSelection = types.joinToString(" OR ") { "$mediaType = ?" }
    val selection = "($typeSelection) AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
    val args = types.map(Int::toString) + "%${escapeLike(query)}%"
    val ids = mutableListOf<String>()
    var permissionRequired = false
    try {
      resolver.query(
        MediaStore.Files.getContentUri("external"),
        arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME),
        selection,
        args.toTypedArray(),
        "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC",
      )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        while (cursor.moveToNext() && ids.size <= limit) {
          ids += cursor.getLong(idColumn).toString()
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: SecurityException) {
      permissionRequired = true
      Log.i(TAG, "MediaStore filename search requires media permission: ${error.message}")
    }
    val truncated = ids.size > limit
    val assets = ids.take(limit).mapNotNull { id ->
      try {
        photoLibrary.fetchAsset(id)
      } catch (error: SecurityException) {
        permissionRequired = true
        null
      }
    }
    MediaStoreSearchResult(assets, permissionRequired || !hasMediaStoreReadAccess(), truncated)
  }

  private suspend fun searchVisualIndex(
    query: String,
    mediaKind: String,
    limit: Int,
  ): VisualSearchResult? {
    val model = findDownloadedEmbeddingModel() ?: return null
    val modelPath = model.getPath(appContext)
    if (!File(modelPath).isFile) return null
    val databasePath = GemmaEmbeddingModelStore.getDatabasePath(
      modelPath,
      DefaultSemanticRetrievalServiceProvider.USER_DATABASE_NAME,
      appContext,
    )
    if (!File(databasePath).isFile) return VisualSearchResult(emptyList(), false)

    val provider = DefaultSemanticRetrievalServiceProvider.create(
      context = appContext,
      model = model,
      maxInputSequenceLength = SmartAlbumViewModel.DEFAULT_MAX_INPUT_SEQUENCE_LENGTH,
      visionTokenBudget = SmartAlbumViewModel.DEFAULT_VISION_TOKEN_BUDGET,
      visionAccelerator = model.backendSpec.visionAccelerator,
      audioAccelerator = model.backendSpec.audioAccelerator,
    )
    return try {
      val service = provider.service(SmartAlbumSource.USER_PHOTOS)
      if (service.fetchRecordIdentifiers().isEmpty()) {
        VisualSearchResult(emptyList(), true)
      } else {
        val results = service.retrieveEntities(query, limit = limit)
        val assets = results.mapNotNull { result ->
          val asset = photoLibrary.fetchAsset(result.id) ?: return@mapNotNull null
          if (!matchesKind(asset, mediaKind)) return@mapNotNull null
          asset to result.similarityScore
        }
        VisualSearchResult(assets, true)
      }
    } finally {
      provider.close()
    }
  }

  private fun hasMediaStoreReadAccess(): Boolean {
    val permissions = when {
      Build.VERSION.SDK_INT >= 34 -> listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      )
      Build.VERSION.SDK_INT >= 33 -> listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
      )
      else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return permissions.any { permission ->
      appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
  }

  private fun findDownloadedEmbeddingModel(): Model? =
    models.firstOrNull { model ->
      model.modelId.contains("embeddinggemma-2", ignoreCase = true) &&
        File(model.getPath(appContext)).isFile
    }

  private suspend fun scanGrantedTrees(
    query: String?,
    mediaKind: String,
    maxMatches: Int,
  ): TreeScanResult = withContext(Dispatchers.IO) {
    val grants = resolver.persistedUriPermissions.filter { permission ->
      DocumentsContract.isTreeUri(permission.uri) && permission.isReadPermission
    }
    val assets = mutableListOf<PhotoAsset>()
    var visited = 0
    var truncated = false
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    for (grant in grants) {
      if (assets.size >= maxMatches) {
        truncated = true
        break
      }
      val treeUri = grant.uri
      val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
      val queue = ArrayDeque<TreeNode>()
      queue.add(TreeNode(rootId, 0))
      while (queue.isNotEmpty()) {
        if (visited >= MAX_TREE_VISITED_NODES || assets.size >= maxMatches) {
          truncated = true
          break
        }
        val node = queue.removeFirst()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
        currentCoroutineContext().ensureActive()
        val cursor = try {
          resolver.query(childrenUri, projection, null, null, null)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.w(TAG, "Unable to inspect a granted storage folder: ${error.message}")
          null
        } ?: continue
        cursor.use {
          while (it.moveToNext()) {
            if (visited >= MAX_TREE_VISITED_NODES || assets.size >= maxMatches) {
              truncated = true
              break
            }
            visited++
            val documentId = it.stringColumn(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
            val displayName = it.stringColumn(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
            val mimeType = it.stringColumn(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty()
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
              if (node.depth < MAX_TREE_DEPTH) queue.add(TreeNode(documentId, node.depth + 1))
              else truncated = true
              continue
            }
            if (!isSupportedMediaMime(mimeType) || !matchesKind(mimeType, mediaKind)) continue
            if (query != null && !displayName.contains(query, ignoreCase = true)) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val size = it.longColumn(DocumentsContract.Document.COLUMN_SIZE) ?: 0L
            val modified = it.longColumn(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0L
            assets += PhotoAsset(
              id = uri.toString(),
              contentUri = uri,
              dateTaken = modified,
              sizeBytes = size.coerceAtLeast(0L),
              displayName = safeDisplayName(displayName),
              isVideo = mimeType.startsWith("video/"),
            )
          }
        }
      }
    }
    TreeScanResult(assets, visited, truncated)
  }

  private fun pruneExpiredHandles() {
    val now = System.currentTimeMillis()
    references.entries.removeAll { it.value.expiresAtMs <= now }
    while (references.size > MAX_STORED_HANDLES) {
      val oldest = references.entries.minByOrNull { it.value.expiresAtMs } ?: break
      references.remove(oldest.key, oldest.value)
    }
  }

  private fun matchesKind(asset: PhotoAsset, mediaKind: String): Boolean = when (mediaKind) {
    "VIDEO" -> asset.isVideo
    "IMAGE" -> !asset.isVideo
    else -> true
  }

  private fun matchesKind(mimeType: String, mediaKind: String): Boolean = when (mediaKind) {
    "VIDEO" -> mimeType.startsWith("video/")
    "IMAGE" -> mimeType.startsWith("image/")
    else -> isSupportedMediaMime(mimeType)
  }

  private fun isSupportedMediaMime(mimeType: String): Boolean =
    mimeType.startsWith("image/") || mimeType.startsWith("video/")

  private fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  private fun safeDisplayName(value: String): String =
    value.filterNot { it.isISOControl() }.take(MAX_DISPLAY_NAME_CHARS).ifBlank { "Untitled media" }

  private fun Cursor.stringColumn(name: String): String? {
    val index = getColumnIndex(name)
    return if (index >= 0 && !isNull(index)) getString(index) else null
  }

  private fun Cursor.longColumn(name: String): Long? {
    val index = getColumnIndex(name)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
  }

  private data class MediaStoreSearchResult(
    val assets: List<PhotoAsset>,
    val permissionRequired: Boolean,
    val truncated: Boolean,
  )
  private data class TreeNode(val documentId: String, val depth: Int)
  private data class TreeScanResult(val assets: List<PhotoAsset>, val visited: Int, val truncated: Boolean)
  private data class VisualSearchResult(val results: List<Pair<PhotoAsset, Float?>>, val indexAvailable: Boolean)

  companion object {
    const val DEFAULT_RESULT_LIMIT = 10
    const val MAX_RESULT_LIMIT = 20
    const val MAX_QUERY_CHARS = 120
    private const val MAX_DISPLAY_NAME_CHARS = 180
    private const val MAX_TREE_DEPTH = 8
    private const val MAX_TREE_VISITED_NODES = 10_000
    private const val MAX_TREE_SEARCH_MATCHES = 100
    private const val MAX_TREE_INDEX_ASSETS = 1_000
    private const val MAX_STORED_HANDLES = 100
    private const val HANDLE_TTL_MS = 5 * 60 * 1_000L
    private const val MAX_DURATION_MS = 24 * 60 * 60 * 1_000L
    private val MEDIA_KINDS = setOf("ANY", "IMAGE", "VIDEO")
    private val SEARCH_MODES = setOf("AUTO", "NAME", "VISUAL")
  }
}
