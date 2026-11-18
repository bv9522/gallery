/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.tools.AskArtifactGenerationPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import java.io.FilterOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

data class ArtifactGenerationApproval(
  val format: ArtifactFormat,
  val fileName: String,
  val destinationLabel: String,
  val estimatedBytes: Long?,
  val overwrite: Boolean,
)

fun interface ArtifactGenerationApprovalRequester {
  suspend fun requestApproval(approval: ArtifactGenerationApproval): Boolean
}

class ChannelArtifactGenerationApprovalRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
) : ArtifactGenerationApprovalRequester {
  override suspend fun requestApproval(approval: ArtifactGenerationApproval): Boolean {
    val action = AskArtifactGenerationPermissionAction(
      format = approval.format.name,
      fileName = approval.fileName,
      destination = approval.destinationLabel,
      estimatedBytes = approval.estimatedBytes,
      overwrite = approval.overwrite,
    )
    return try {
      actionChannel.send(action)
      withTimeoutOrNull(timeoutMs) { action.result.await() } ?: false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }

  companion object {
    const val DEFAULT_APPROVAL_TIMEOUT_MS = 120_000L
  }
}

data class ArtifactOutputMetadata(
  val mimeType: String,
  val bytesWritten: Long,
  val sha256: String,
)

data class ArtifactDestinationWriteResult(
  val destinationLabel: String,
  val metadata: ArtifactOutputMetadata,
)

data class ArtifactGenerationResult(
  val isSuccessful: Boolean,
  val format: String,
  val fileName: String,
  val destination: String,
  val mimeType: String?,
  val bytesWritten: Long?,
  val sha256: String?,
  val message: String,
)

fun interface ArtifactOutputProducer {
  suspend fun write(output: OutputStream): ArtifactOutputMetadata
}

interface ArtifactGenerator {
  val format: ArtifactFormat
  val maxOutputBytes: Long
  fun estimateBytes(request: ArtifactCreateRequest): Long? = null
  fun write(request: ArtifactCreateRequest, output: OutputStream): String
}

interface ArtifactDestinationAdapter {
  val destinationType: DestinationType
  suspend fun describe(request: ArtifactCreateRequest): String
  suspend fun write(request: ArtifactCreateRequest, producer: ArtifactOutputProducer): ArtifactDestinationWriteResult
}

enum class DestinationType { SAF, SHELL }

class ArtifactGeneratorRegistry(generators: List<ArtifactGenerator>) {
  private val byFormat = generators.associateBy { it.format }
  init {
    require(byFormat.size == generators.size) { "Only one generator may own each artifact format." }
    require(generators.all { it.maxOutputBytes > 0L }) { "Every artifact generator must declare a positive output limit." }
  }
  fun find(format: ArtifactFormat): ArtifactGenerator? = byFormat[format]
}

class ArtifactDestinationRegistry(adapters: List<ArtifactDestinationAdapter>) {
  private val byType = adapters.associateBy { it.destinationType }
  init {
    require(byType.size == adapters.size) { "Only one destination adapter may own each destination type." }
  }
  fun find(type: DestinationType): ArtifactDestinationAdapter? = byType[type]
}

class DefaultArtifactGenerationEngine(
  private val generators: ArtifactGeneratorRegistry,
  private val destinations: ArtifactDestinationRegistry,
  private val approvalRequester: ArtifactGenerationApprovalRequester,
  private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
) {
  suspend fun create(request: ArtifactCreateRequest): ArtifactGenerationResult {
    val generator = generators.find(request.format)
      ?: return failure(request, "The ${request.format.name.lowercase()} generator is not available yet.")
    val destinationType = when (request.destination) {
      is ArtifactDestinationSpec.SafTree -> DestinationType.SAF
      is ArtifactDestinationSpec.ShellPath -> DestinationType.SHELL
    }
    val destination = destinations.find(destinationType)
      ?: return failure(request, "The selected destination adapter is not available.")
    val destinationLabel = try {
      destination.describe(request)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return failure(request, error.message ?: "The destination could not be inspected.")
    }
    val approval = ArtifactGenerationApproval(
      format = request.format,
      fileName = request.fileName,
      destinationLabel = destinationLabel,
      estimatedBytes = generator.estimateBytes(request),
      overwrite = request.overwrite,
    )
    val approved = try {
      approvalRequester.requestApproval(approval)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return failure(request, error.message ?: "Artifact approval could not be completed.")
    }
    if (!approved) return failure(request, "Artifact generation was not approved.")
    return try {
      val written = withTimeoutOrNull(operationTimeoutMs) {
        destination.write(request) { rawOutput ->
          val digest = MessageDigest.getInstance("SHA-256")
          val prefix = ByteArrayOutputStream(16)
          var count = 0L
          val bounded = object : FilterOutputStream(rawOutput) {
            private fun capture(bytes: ByteArray, offset: Int, length: Int) {
              val remaining = 16 - prefix.size()
              if (remaining > 0) prefix.write(bytes, offset, minOf(length, remaining))
            }
            override fun write(value: Int) {
              count++
              require(count <= generator.maxOutputBytes) { "Artifact exceeds the format output limit." }
              digest.update(value.toByte())
              if (prefix.size() < 16) prefix.write(value)
              out.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
              require(length >= 0 && count + length <= generator.maxOutputBytes) {
                "Artifact exceeds the format output limit."
              }
              count += length
              digest.update(bytes, offset, length)
              capture(bytes, offset, length)
              out.write(bytes, offset, length)
            }
          }
          val mimeType = generator.write(request, bounded)
          require(request.format.acceptsMimeType(mimeType)) {
            "The generator returned MIME type '$mimeType' for format ${request.format.name.lowercase()}."
          }
          require(request.format.acceptsSignature(prefix.toByteArray())) {
            "The generated output does not have a valid ${request.format.name.lowercase()} file signature."
          }
          bounded.flush()
          ArtifactOutputMetadata(
            mimeType,
            count,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
          )
        }
      } ?: return failure(request, "Artifact generation exceeded the operation time limit.")
      ArtifactGenerationResult(
        true, request.format.name.lowercase(), request.fileName, written.destinationLabel,
        written.metadata.mimeType, written.metadata.bytesWritten, written.metadata.sha256,
        "Created ${request.fileName} (${written.metadata.bytesWritten} bytes).",
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(request, error.message ?: error.javaClass.simpleName)
    }
  }

  private fun failure(request: ArtifactCreateRequest, message: String) = ArtifactGenerationResult(
    false, request.format.name.lowercase(), request.fileName, "Not written", null, null, null, message,
  )

  companion object {
    const val DEFAULT_OPERATION_TIMEOUT_MS = 120_000L
  }
}
