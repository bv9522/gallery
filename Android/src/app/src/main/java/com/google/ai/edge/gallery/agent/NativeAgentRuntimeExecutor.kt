/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.ai.edge.gallery.agent.coordinator.AgentCoordinator
import com.google.ai.edge.gallery.agent.coordinator.AgentRunResult
import com.google.ai.edge.gallery.agent.coordinator.AgentToolRegistry
import com.google.ai.edge.gallery.agent.coordinator.ChannelShellExecutionApprovalPolicy
import com.google.ai.edge.gallery.agent.coordinator.LiteRtLmAgentModel
import com.google.ai.edge.gallery.agent.coordinator.ShizukuShellTransport
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRouter
import com.google.ai.edge.gallery.agent.coordinator.TermuxRishShellTransport
import com.google.ai.edge.gallery.agent.coordinator.TermuxShellTransport
import com.google.ai.edge.gallery.agent.coordinator.UnifiedShellTool
import com.google.ai.edge.gallery.agent.capability.AndroidNativeCapabilityTool
import com.google.ai.edge.gallery.agent.capability.ActiveMediaSessionCapabilityTool
import com.google.ai.edge.gallery.agent.capability.AccessibilityScreenInspectionTool
import com.google.ai.edge.gallery.agent.capability.ChannelNativeCapabilityAuthorizer
import com.google.ai.edge.gallery.agent.capability.GalleryMediaLibraryOperations
import com.google.ai.edge.gallery.agent.capability.ShareContentCapabilityTool
import com.google.ai.edge.gallery.agent.capability.DocumentTreeCapabilityTool
import com.google.ai.edge.gallery.agent.capability.TermuxApiCapabilityTool
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.ai.edge.gallery.shizuku.ShizukuManagerPackageResolver
import com.google.ai.edge.gallery.termux.TermuxRunCommandClient
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps native shell execution distinct from the external MCP wrapper for small on-device models.
 * Backend choice is explicit and is never inferred from whichever transport happens to be alive.
 */
internal fun buildAgentSystemInstruction(existing: String?): String = buildString {
  existing?.takeIf { it.isNotBlank() }?.let {
    append(it.trim())
    append("\n\n")
  }
  append(
    "SCREEN INSPECTION, VISION, AND UI ACTION RULES: Use screen_inspect operation=status to check Gallery accessibility and capability availability. Use operation=inspect for a read-only semantic summary of the active window. Use operation=inspect_external only when the user asks to inspect or interact with another app and exactly one target app is visible alongside Gallery (normally split-screen); it requires one-time UI_TREE_READ approval and returns short-lived opaque handles for non-editable semantic elements. Use operation=act only for an explicit user-requested action and only with a fresh handle from the latest inspect_external result. Supported actions are CLICK, LONG_CLICK, SCROLL_FORWARD, and SCROLL_BACKWARD; each requires one-time UI_GESTURE approval describing the exact action and target. Immediately before dispatch, Gallery revalidates the same app/window, complete safe hierarchy, exact node class/label/bounds, enabled state, and currently advertised action. Handles are one-shot; stale, ambiguous, changed, incomplete, or sensitive targets fail closed. Use operation=inspect_inputs only when the user explicitly asks to enter or select text; it requires UI_TREE_READ approval and inspects exactly one external app window. It returns safe labels and character counts, never current field contents. It accepts only ordinary single-line plain-text EditText fields. Password, numeric, email, URI, multiline, sensitive, or uncertain fields block the entire inspection. Use operation=enter_text only with a fresh input handle and only for an empty field. It accepts at most 256 ordinary single-line non-sensitive characters, never overwrites existing text, and requires separate UI_TEXT_INPUT approval displaying the exact text and target. Use operation=select_text only with a fresh input handle and a valid end-exclusive character range; it requires separate UI_TEXT_INPUT approval displaying the exact range. Input handles are one-shot and expire after 30 seconds. The service revalidates app/window, hierarchy, exact node class/label/bounds, input type, enabled state, and current text length immediately before dispatch. No passwords, clipboard, coordinate gestures, arbitrary keyboard events, hidden typing, or app switching are exposed. A successful result means Android accepted dispatch only, not that the app's resulting state was verified. Use operation=capture only when the user explicitly asks Gemma to visually inspect an app screen. Capture is exposed only when the selected model and runtime configuration support image input; it requires one-time SCREEN_CAPTURE approval, Android API 34+, and exactly one visible non-Gallery app window arranged alongside Gallery (normally split-screen). It checks the target accessibility hierarchy first and fails closed for sensitive, incomplete, empty, or ambiguous windows. It captures only that window using takeScreenshotOfWindow; never use full-display screenshot or MediaProjection fallback. The image is bounded to 1280 px / 2 MiB, passed as actual image content in the multimodal tool response to the local Gemma model, with raw coordinator bytes erased after inference; the model conversation may retain visual context in memory until reset. When capture returns an image, inspect the actual pixels to answer the user’s visual question; never claim to have seen an image when capture failed or the image payload was omitted. Do not claim sensitive-screen detection is perfect; ask the user to avoid private/sensitive screens. Never use screen inspection for credentials, payments, banking, health records, or private account details. The user must manually enable Gallery in Android Accessibility settings. No background monitoring or persistent screenshot files are created. " +
    "DEVICE CONTROL TOOL RULES: Use android_control download_status to inspect up to 20 active Gallery model downloads. " +
      "Use cancel_download only after download_status, passing the exact opaque download_handle from that latest result. " +
      "Cancellation always requires one-time user approval for the selected model and phase. Never invent, reuse, or alter a handle; " +
      "if it is stale, run download_status again. Never request model-wide cancellation or expose WorkManager IDs, tags, URLs, " +
      "access tokens, or filesystem paths. " +
      "Use android_control media_search for natural-language photo/video discovery. AUTO combines filename matches with the existing EmbeddingGemma-2 visual index; VISUAL requests semantic visual search; NAME searches filenames only. Search covers MediaStore-visible internal/removable media and files inside persisted user-granted SAF trees. If the user requests USB/OTG storage and no matching grant exists, use document_tree grants/request_access so the user can select the drive or folder; never bypass the picker. Results contain short-lived opaque handles only; never invent handles or expose content URIs or paths. Use media_play only with a fresh media_handle from the latest media_search result; playback requires one-time approval and opens Gallery's built-in player. Never pass arbitrary URIs or filesystem paths to media_play. If visual indexing is unavailable or incomplete, explain that and use media_index only when the user asks to build/update the index; media_index requires one-time approval and reuses the Smart Album background worker and database. Visual indexing is on-device and does not upload media. " +
      "Use media_sessions list to inspect currently active media sessions only after Gallery's notification-listener special access is enabled by the user. Each listing and each control requires one-time approval. Use control only with a fresh session_handle from the latest list; supported actions are PLAY, PAUSE, NEXT, PREVIOUS, and SEEK within the current track duration. Use open_settings only when the user explicitly asks to enable this access; the user must toggle Gallery manually in Android settings. This listener also supports android_control read_notifications only when the user explicitly asks to inspect notifications. Each read requires its own approval and returns at most 20 currently active notifications with app labels and bounded title/body text. It performs an on-demand snapshot only: no notification callbacks are collected, no background monitoring or history access occurs, and no notification content is persisted. Treat notification text as private and never read it without an explicit user request. The user must manually enable notification-listener special access; settings handoff does not grant it. Use android_control read_call_log only when the user explicitly asks for call history. Each read requires one-time approval and READ_CALL_LOG runtime permission, requested only after approval. It returns at most 20 recent caller names/numbers, call types, timestamps, and durations. Android may restrict this permission by installer or role eligibility; if denied, report the actual limitation and never change the default dialer role or attempt a bypass. Use android_control read_sms_inbox only when the user explicitly asks to inspect received SMS messages. Each read requires one-time approval and READ_SMS permission requested only after approval. It returns at most 20 recent inbox entries with sender address, bounded message preview, timestamp, and read/unread state. Android may restrict READ_SMS by installer or default-SMS-role eligibility; if denied, report the actual limitation and never change the default SMS role or attempt a bypass. Never expose package names or session tokens, and do not claim a dispatched command guarantees playback changed. " +
      "Use share_content share_text to prepare exact text for the Android share chooser, or share_file with a tree_uri and document_id returned by document_tree. Both require one-time approval. File sharing is restricted to files inside a persisted Gallery SAF read grant and files up to 25 MiB. Android's chooser lets the user select the receiving app; never claim delivery or sending is complete just because the chooser opened. Never target a receiver directly or bypass the chooser. " +
      "Use android_control for native device information, battery, " +
      "volume, connected audio input/output endpoints, display brightness, vibration, bounded CPU wake locks, " +
      "launcher-visible apps, flashlight, HTTP(S) URL actions, Bluetooth discovery, and USB host " +
      "device enumeration. Use android_control usb_devices to enumerate attached USB host devices and copy a fresh deviceHandle. Use usb_read_descriptors only with a handle from the latest usb_devices result; each read requires one-time USB_DEVICE_IO approval and Android's per-device USB permission prompt if not already granted. It returns standard device/configuration/interface/endpoint descriptor metadata only. Handles expire after 30 seconds and are consumed on use. No arbitrary control, bulk, interrupt, or isochronous endpoint transfers or writes are exposed. USB mass-storage files must be accessed through the user-granted SAF document_tree provider, never UsbManager raw I/O. Use record_audio for a local AAC recording of 1–30 seconds saved to Music/EdgeGallery; " +
      "it requires one-time approval and RECORD_AUDIO permission and returns a local content URI. " +
      "Use capture_photo for a user-mediated camera capture saved to Pictures/EdgeGallery; it requires " +
      "one-time approval. Use set_wallpaper only with a Gallery-readable image content URI and SYSTEM, " +
      "LOCK, or BOTH target; it requires one-time approval. Use bluetooth_bonded_devices with " +
      "BLUETOOTH_CONNECT and bluetooth_le_scan with BLUETOOTH_SCAN plus CONNECT; both require approval. " +
      "Use document_tree for user-granted SAF folders and attached USB storage: " +
      "request_access opens the Android picker, grants returns persisted tree URIs and root document IDs, " +
      "and list/read_text/create_file/create_directory/write_text/rename/delete/metadata operate only " +
      "inside those exact persisted grants. Do not pass raw filesystem paths or invent tree/document IDs. " +
      "Text reads/writes are limited to 256 KiB; listing is limited to 100 entries. " +
      "Copy, move, recursive search, and general metadata writes are not implemented. " +
      "Use audio_devices to inspect " +
      "connected audio endpoints. display_brightness reports the stored system setting and mode; " +
      "setting brightness requires Android's Modify system settings special access and switches " +
      "to manual mode only after the user approves the exact change. Vibration is limited to 1 second. " +
      "Wake locks are partial CPU locks capped at 5 minutes and auto-release at timeout; use " +
      "wake_lock_status to inspect and set_wake_lock with held=false to release. Use list_apps before " +
      "launch_app and pass an exact returned package_name. Never guess package names. Read-only " +
      "ordinary status and non-sensitive reads do not require approval; privacy-sensitive device " +
      "discovery, microphone/photo capture, document reads, and all mutations require one-time approval. " +
      "Report unavailable capabilities and permission requirements honestly. When a tool returns an argument-validation error with accepted arguments, retry once using the same operation and only those accepted arguments; never infer or substitute another operation. Do not use shell " +
      "commands as an automatic fallback for android_control failures. When reporting device " +
      "identity, copy modelIdentifier exactly character-for-character from device_info; do not " +
      "remove punctuation, alter digits, or infer a product tier. When reporting battery status, " +
      "copy batteryPercent and batteryState exactly; batteryState is decoded text, and raw Android " +
      "integer constants must never be presented as charging states. When reporting volume, preserve " +
      "the exact current and maximum values returned by volume_status and name the stream. For " +
      "list_apps, obey returned, total, truncated, and remaining: if truncated=true, explicitly say " +
      "the list is truncated and state how many apps remain; never say it is complete. For " +
      "android_control operation=status, report the native provider availability diagnostic and its " +
      "capability states; do not substitute unrelated skill/tool inventories, and do not describe " +
      "provider availability as a live battery or volume reading.\n\n" +
      "TERMUX API RULES: Use termux_api only for its explicit allowlisted Termux:API operations. Start with " +
      "status to inspect the Termux:API companion and fixed command availability; permission state " +
      "remains unknown until an operation reports otherwise. Use sensor_list before sensor_read; " +
      "sensor_read accepts only names from that current listing and performs at most 10 finite samples " +
      "with a 100–5000 ms delay. Location and Wi-Fi reads require the user's scoped approval because " +
      "they can reveal sensitive location or network details. Use location only with request once or " +
      "last and provider gps, network, or passive. Never request sensor streams or location updates; " +
      "those long-running modes are not exposed. Use wifi_connection_info for current connection data " +
      "and wifi_last_scan for the last scan only; it does not trigger a new scan and may be stale. " +
      "Treat permission errors distinctly " +
      "and never claim a command is available merely because its binary exists. Use infrared_frequencies " +
      "to inspect supported carrier frequencies before infrared_transmit; transmission accepts only a " +
      "reported frequency and a numeric pulse pattern shorter than two seconds, and always requires " +
      "one-time approval. Use tts_engines to inspect installed speech engines; tts_speak is limited to " +
      "500 characters and requires approval because it speaks aloud. Never use these operations as " +
      "a substitute for arbitrary shell commands. speech_to_text starts one interactive recognition " +
      "session and requires approval; tell the user that audio may be processed by the configured " +
      "recognition service. NFC reads and writes require one-time approval; " +
      "NFC write accepts text-only NDEF payloads up to 512 characters and may replace existing tag " +
      "content. Present the intended tag when the Termux NFC interaction opens. These are bounded " +
      "single interactions, not background NFC sessions. Use android_control " +
      "for native operations unless the user explicitly requests Termux:API or is comparing providers. " +
      "Use record_audio for a bounded 1–30 second local AAC recording saved to Music/EdgeGallery; " +
      "it requires one-time MICROPHONE_RECORD approval and RECORD_AUDIO permission, returns a local " +
      "content URI, and does not upload or transcribe the audio. " +
      "Use capture_photo for one user-mediated camera capture saved to a Gallery-owned MediaStore URI; " +
      "it requires one-time approval. Use set_wallpaper only with a Gallery-readable image content " +
      "URI and a target of SYSTEM, LOCK, or BOTH; it requires one-time approval. " +
      "Use bluetooth_bonded_devices to list paired devices (one-time approval plus BLUETOOTH_CONNECT) " +
      "and bluetooth_le_scan to discover nearby BLE devices (one-time approval plus BLUETOOTH_SCAN " +
      "and CONNECT). BLE scans " +
      "last 1–10 seconds and must stop on cancellation. " +
      "Never invent Termux:API commands or use this tool as arbitrary shell access. Its mutations " +
      "require the same one-time approval boundary.\n\n" +
      "DEVICE SHELL TOOL RULES: For any request that requires a device shell command, call the " +
      "native execute_shell tool directly. Do not call run_mcp_tool to execute shell commands; " +
      "run_mcp_tool is only for tools hosted by external MCP servers. The execute_shell tool " +
      "requires an explicit backend and command. If the user names a backend, use that exact " +
      "backend. TERMUX, SHIZUKU, and TERMUX_RISH are distinct identities. Never silently switch " +
      "or fall back to another backend. If the tool returns an error, report that actual error " +
      "instead of guessing that a service is stopped. For a multi-part information request, " +
      "retrieve every requested field and clearly mark any field that could not be retrieved."
  )
}

/**
 * Agent Chat runtime backed by one coordinator and one manually-driven LiteRT-LM conversation.
 *
 * Existing Gallery tools are supplied to the same conversation ToolManager and adapted back into
 * AgentTools, so skills, MCP, JS, intents, and native shell all pass through AgentCoordinator.
 * AICore remains on the established executor because it does not expose the LiteRT-LM Conversation
 * API used by the manual structured-tool-call adapter.
 */
class NativeAgentRuntimeExecutor(
  private val agentTools: AgentTools,
  private val fallbackExecutor: AgentRuntimeExecutor,
  private val downloadRepository: DownloadRepository? = null,
) : AgentRuntimeExecutor {

  private val stateLock = Any()
  private val activeJob = AtomicReference<Job?>(null)
  @Volatile private var context: Context? = null
  @Volatile private var config: AgentRuntimeConfig? = null
  @Volatile private var agentModel: LiteRtLmAgentModel? = null
  @Volatile private var coordinator: AgentCoordinator? = null
  @Volatile private var shellRouter: ShellExecutionRouter? = null
  @Volatile private var shizukuClient: ShizukuShellClient? = null
  @Volatile private var usingFallback = false
  @Volatile private var sessionId: String? = null

  override val activeSessionId: String?
    get() = if (usingFallback) fallbackExecutor.activeSessionId else sessionId

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (errorMsg: String) -> Unit,
  ) {
    this.context = context.applicationContext
    this.config = config
    sessionId = config.sessionId.ifBlank { UUID.randomUUID().toString() }

    if (config.model.isAiCore) {
      usingFallback = true
      fallbackExecutor.initialize(context, config, onDone)
      return
    }

    usingFallback = false
    attachLegacyToolContext(config)
    val appContext = context.applicationContext
    val termux = TermuxRunCommandClient(appContext)
    val shizuku = ShizukuShellClient(appContext)
    shizukuClient = shizuku
    shellRouter = ShellExecutionRouter(
      mapOf(
        ShellExecutionBackend.TERMUX to TermuxShellTransport(termux),
        ShellExecutionBackend.SHIZUKU to ShizukuShellTransport(shizuku),
        ShellExecutionBackend.TERMUX_RISH to TermuxRishShellTransport(termux) {
          ShizukuManagerPackageResolver.resolve(appContext)
        },
      )
    )

    // Initialize the shared engine only. The agent gets its own manual-tool-calling conversation
    // after initialization, leaving Gallery's ordinary conversation and MCP client intact.
    config.model.runtimeHelper.initialize(
      context = context.applicationContext,
      model = config.model,
      taskId = config.taskId,
      supportImage = config.supportImage,
      supportAudio = config.supportAudio,
      onDone = onDone,
      systemInstruction = config.systemInstruction?.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
      tools = emptyList(),
      enableConversationConstrainedDecoding = false,
      initialMessages = emptyList(),
    )
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> {
    if (usingFallback) return fallbackExecutor.executeStream(context, request)
    return flow {
      val job = currentCoroutineContext()[Job]
      activeJob.set(job)
      emit(AgentEvent.LoopInitiated(request))
      var coordinatorStarted = false
      try {
        val currentConfig = checkNotNull(config) { "Agent Chat has not been initialized." }
        val requestedSessionId = request.metadata[AgentRequest.SESSION_ID] as? String
        if (!requestedSessionId.isNullOrBlank() && requestedSessionId != sessionId) {
          sessionId = requestedSessionId
          discardAgentConversation()
        }
        val currentContext = checkNotNull(this@NativeAgentRuntimeExecutor.context)
        val contents = withContext(Dispatchers.IO) { buildUserContents(currentContext, request) }
        ensureAgentModel(currentConfig)
        val currentCoordinator = checkNotNull(coordinator)
        coordinatorStarted = true
        @Suppress("UNCHECKED_CAST")
        val extraContext = request.metadata[AgentRequest.LITERTLM_EXTRA_CONTEXT] as? Map<String, Any>
          ?: emptyMap()
        val result: AgentRunResult =
          currentCoordinator.run(request.query, contents, extraContext)
        if (result.isSuccessful) {
          // Gemma's final prose can miscopy identifiers, counts, and command output. Append the
          // coordinator-retained, budgeted tool payload verbatim so the user can inspect the
          // authoritative result without relying on model transcription.
          val authoritativeResults = formatAuthoritativeToolResults(result.messages)
          val finalText = if (authoritativeResults.isNullOrBlank()) {
            result.finalText
          } else {
            result.finalText + "\n\n" + authoritativeResults
          }
          emit(AgentEvent.StreamToken(token = finalText, done = true))
          emit(AgentEvent.LoopTerminated(finalText))
        } else {
          discardAgentConversation()
          emit(AgentEvent.Error(result.finalText))
        }
      } catch (cancelled: CancellationException) {
        discardAgentConversation()
        throw cancelled
      } catch (error: Exception) {
        if (coordinatorStarted) discardAgentConversation()
        emit(AgentEvent.Error(error.message ?: error.javaClass.simpleName))
      } finally {
        activeJob.compareAndSet(job, null)
      }
    }
  }

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse {
    if (usingFallback) return fallbackExecutor.execute(context, request)
    var output = ""
    var success = true
    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.LoopTerminated -> output = event.finalResponse
        is AgentEvent.Error -> {
          output = event.errorMessage
          success = false
        }
        else -> Unit
      }
    }
    return AgentResponse(output, success)
  }

  override fun interrupt() {
    if (usingFallback) {
      fallbackExecutor.interrupt()
      return
    }
    agentModel?.interrupt()
    activeJob.getAndSet(null)?.cancel(CancellationException("Agent execution interrupted."))
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    if (usingFallback) {
      fallbackExecutor.resetSession(config)
      return
    }
    interrupt()
    val previousConfig = this.config
    val effectiveConfig = config.copy(
      actionChannel = config.actionChannel ?: previousConfig?.actionChannel ?: agentTools.sendActionChannel,
      // Agent Chat's stop/restart path rebuilds a partial config. Preserve the established
      // constrained-decoding requirement instead of silently disabling tool-call constraints.
      enableConversationConstrainedDecoding =
        config.enableConversationConstrainedDecoding ||
          previousConfig?.enableConversationConstrainedDecoding == true,
    )
    this.config = effectiveConfig
    sessionId = effectiveConfig.sessionId.ifBlank { sessionId ?: UUID.randomUUID().toString() }
    attachLegacyToolContext(effectiveConfig)
    discardAgentConversation()
  }

  override fun cleanUp(onDone: () -> Unit) {
    if (usingFallback) {
      fallbackExecutor.cleanUp(onDone)
      return
    }
    val model = config?.model
    val shellClient = shizukuClient
    activeJob.getAndSet(null)?.cancel(CancellationException("Agent runtime cleaned up."))
    discardAgentConversation()
    shizukuClient = null
    shellRouter = null
    config = null
    sessionId = null
    CoroutineScope(Dispatchers.IO).launch {
      runCatching { shellClient?.close() }
      if (model == null) {
        onDone()
      } else {
        model.runtimeHelper.cleanUp(model, onDone)
      }
    }
  }

  private fun attachLegacyToolContext(config: AgentRuntimeConfig) {
    RuntimeToolDispatcher().setupExecutionContext(
      tools = agentTools.getAvailableTools(),
      context = ToolExecutionContext(
        taskId = config.taskId,
        actionChannel = config.actionChannel,
      ),
    )
  }

  private suspend fun ensureAgentModel(config: AgentRuntimeConfig): LiteRtLmAgentModel =
    withContext(Dispatchers.IO) {
      synchronized(stateLock) {
        agentModel?.let { return@synchronized it }
        val router = checkNotNull(shellRouter) { "Shell execution backends are not initialized." }
        val actionChannel = config.actionChannel ?: agentTools.sendActionChannel
        val approval = ChannelShellExecutionApprovalPolicy(actionChannel)
        val mediaOperations = GalleryMediaLibraryOperations(
          context = checkNotNull(this@NativeAgentRuntimeExecutor.context),
          modelsProvider = {
            runCatching { agentTools.modelManagerViewModel.getAllModels() }.getOrDefault(emptyList())
          },
          authorizer = ChannelNativeCapabilityAuthorizer(actionChannel),
        )
        val runtimeTools = AgentToolRegistry(
          listOf(
            UnifiedShellTool(router, approval),
            AndroidNativeCapabilityTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
              downloadOperations = downloadRepository,
              mediaOperations = mediaOperations,
            ),
            ActiveMediaSessionCapabilityTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
            ),
            AccessibilityScreenInspectionTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
              imageInputSupported = config.supportImage && config.model.supportImage,
            ),
            ShareContentCapabilityTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
            ),
            DocumentTreeCapabilityTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
            ),
            TermuxApiCapabilityTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
              client = TermuxRunCommandClient(checkNotNull(context)),
            ),
          )
        )
        val created = com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper.createAgentModel(
          model = config.model,
          tools = runtimeTools,
          systemInstruction = buildAgentSystemInstruction(config.systemInstruction),
          additionalToolProviders = agentTools.getLiteRtToolProviders(),
          initialMessages = config.initialMessages,
          enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
        )
        Log.i(
          TAG,
          "Agent conversation created: model=${config.model.name}, " +
            "accelerator=${config.model.currentAccelerator}, " +
            "constrainedDecoding=${config.enableConversationConstrainedDecoding}",
        )
        agentModel = created
        coordinator = AgentCoordinator(created, created.toolRegistry(runtimeTools))
        created
      }
    }

  private fun discardAgentConversation() {
    synchronized(stateLock) {
      val old = agentModel
      agentModel = null
      coordinator = null
      old?.close()
    }
  }

  private fun buildUserContents(context: Context, request: AgentRequest): Contents? {
    if (request.attachments.isEmpty()) return null
    val contents = mutableListOf<Content>()
    request.attachments.forEach { attachment ->
      when (attachment) {
        is Attachment.ImageBitmap -> {
          val bytes = attachment.bitmap.toPngBytes()
          require(bytes.size <= MAX_ATTACHMENT_BYTES) { "Attached image exceeds the 16 MiB limit." }
          contents += Content.ImageBytes(bytes)
        }
        is Attachment.ImageUri -> contents += Content.ImageBytes(readUri(context, attachment.uri))
        is Attachment.AudioBytes -> {
          require(attachment.audioBytes.size <= MAX_ATTACHMENT_BYTES) {
            "Attached audio exceeds the 16 MiB limit."
          }
          contents += Content.AudioBytes(attachment.audioBytes)
        }
        is Attachment.AudioUri -> contents += Content.AudioBytes(readUri(context, attachment.uri))
        is Attachment.File -> {
          val uri = Uri.parse(attachment.path)
          val fileName = uri.lastPathSegment ?: File(attachment.path).name
          val bytes =
            if (uri.scheme == "content" || uri.scheme == "file") {
              readUri(context, attachment.path)
            } else {
              val file = File(attachment.path)
              require(file.isFile) { "Attached file is unavailable: ${attachment.path}" }
              require(file.length() <= MAX_ATTACHMENT_BYTES) {
                "Attached file exceeds the 16 MiB limit."
              }
              file.readBytes()
            }
          when {
            attachment.mimeType.startsWith("image/") -> contents += Content.ImageBytes(bytes)
            attachment.mimeType.startsWith("audio/") -> contents += Content.AudioBytes(bytes)
            attachment.mimeType.startsWith("text/") -> contents += Content.Text(
              "Attached text file ($fileName):\n" + bytes.toString(Charsets.UTF_8)
            )
            else -> error("Unsupported attachment type: ${attachment.mimeType}")
          }
        }
      }
    }
    if (request.query.isNotBlank()) contents += Content.Text(request.query)
    return Contents.of(contents)
  }

  private fun readUri(context: Context, uri: String): ByteArray {
    val stream = context.contentResolver.openInputStream(Uri.parse(uri))
      ?: error("Could not open attachment URI.")
    return stream.use { input ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var total = 0
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_ATTACHMENT_BYTES) {
          "Attached file exceeds the 16 MiB limit."
        }
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
  }

  private fun Bitmap.toPngBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  private companion object {
    const val TAG = "NativeAgentRuntime"
    const val MAX_ATTACHMENT_BYTES = 16 * 1_024 * 1_024
  }
}
