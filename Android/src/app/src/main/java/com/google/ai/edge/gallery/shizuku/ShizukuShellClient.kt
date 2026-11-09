/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package com.google.ai.edge.gallery.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import rikka.shizuku.Shizuku

/** Injectable execution boundary for tools and tests. */
interface ShizukuShellExecutor {
  suspend fun execute(
    command: String,
    workingDirectory: String = "/",
    timeoutMs: Long = ShizukuShellClient.DEFAULT_TIMEOUT_MS,
    maxOutputChars: Int = ShizukuShellClient.DEFAULT_OUTPUT_LIMIT,
  ): ShizukuShellResult
}

/**
 * App-side client for the Shizuku shell UserService.
 *
 * Binding and permission checks are performed through Shizuku. Binder calls run on IO dispatchers.
 * One persistent shell session is retained for this client instance so cwd and exported variables
 * survive consecutive tool calls. This class does not grant root access.
 */
class ShizukuShellClient(context: Context) : ShizukuShellExecutor {

  private val appContext = context.applicationContext
  private val sessionMutex = Mutex()
  private val stateLock = Any()
  @Volatile private var service: IShizukuShellService? = null
  @Volatile private var sessionId: String? = null
  private var connectionResult: CompletableDeferred<IShizukuShellService>? = null
  private var bound = false
  @Volatile private var closed = false

  private val userServiceArgs by lazy {
    Shizuku.UserServiceArgs(
      ComponentName(appContext.packageName, ShizukuShellUserService::class.java.name)
    )
      .daemon(false)
      .processNameSuffix("shell")
  }

  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val connected = IShizukuShellService.Stub.asInterface(binder)
        synchronized(stateLock) {
          service = connected
          connectionResult?.complete(connected)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        synchronized(stateLock) {
          service = null
          sessionId = null
          connectionResult?.takeIf { !it.isCompleted }
            ?.completeExceptionally(IllegalStateException("Shizuku shell service disconnected."))
          connectionResult = null
        }
      }
    }

  /**
   * Execute one command in this client's persistent shell session.
   *
   * @throws IllegalStateException when Shizuku is unavailable, permission is missing, or the
   * service cannot be bound.
   */
  override suspend fun execute(
    command: String,
    workingDirectory: String,
    timeoutMs: Long,
    maxOutputChars: Int,
  ): ShizukuShellResult {
    require(command.isNotBlank()) { "Command must not be blank." }
    require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
      "Timeout must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms."
    }
    require(maxOutputChars in MIN_OUTPUT_CHARS..MAX_OUTPUT_CHARS) {
      "Output limit must be between $MIN_OUTPUT_CHARS and $MAX_OUTPUT_CHARS characters."
    }
    check(!closed) { "Shizuku shell client is closed." }

    val remote = connect()
    return withContext(Dispatchers.IO) {
      sessionMutex.withLock {
        check(!closed) { "Shizuku shell client is closed." }
        val activeSession = sessionId ?: run {
          val created = remote.createSession(workingDirectory)
          val id = created.getString(ShizukuShellUserService.KEY_SESSION_ID)
            ?: throw IllegalStateException(
              created.getString(ShizukuShellUserService.KEY_ERROR) ?: "Could not create shell session."
            )
          sessionId = id
          id
        }

        val bundle = suspendCancellableCoroutine<android.os.Bundle> { continuation ->
          Dispatchers.IO.dispatch(continuation.context, Runnable {
            try {
              val response = remote.execute(activeSession, command, timeoutMs, maxOutputChars)
              if (continuation.isActive) continuation.resume(response)
            } catch (error: Throwable) {
              if (continuation.isActive) continuation.resumeWithException(error)
            }
          })
          continuation.invokeOnCancellation {
            sessionId = null
            runCatching { remote.cancelSession(activeSession) }
          }
        }
        val error = bundle.getString(ShizukuShellUserService.KEY_ERROR)
        val result = ShizukuShellResult(
          stdout = bundle.getString(ShizukuShellUserService.KEY_STDOUT).orEmpty(),
          stderr = bundle.getString(ShizukuShellUserService.KEY_STDERR).orEmpty(),
          exitCode = bundle.getInt(ShizukuShellUserService.KEY_EXIT_CODE, -1),
          timedOut = bundle.getBoolean(ShizukuShellUserService.KEY_TIMED_OUT, false),
          outputTruncated = bundle.getBoolean(ShizukuShellUserService.KEY_OUTPUT_TRUNCATED, false),
          error = error,
          processMayStillBeRunning = bundle.getBoolean(
            ShizukuShellUserService.KEY_PROCESS_MAY_STILL_BE_RUNNING, false
          ),
        )
        if (result.timedOut || error != null) sessionId = null
        result
      }
    }
  }

  /** Close the persistent shell session and unbind this client from the UserService. */
  suspend fun close() {
    if (closed) return
    closed = true
    // Do not wait behind a blocking Binder execute() call. Cancel the remote process group first,
    // then acquire the session mutex to finish local state teardown and unbind.
    val activeService = service
    val activeSession = sessionId
    if (activeService != null && activeSession != null) {
      withContext(Dispatchers.IO) {
        runCatching { activeService.cancelSession(activeSession) }
      }
    }
    withContext(Dispatchers.IO) {
      sessionMutex.withLock {
        val remote = service
        val id = sessionId
        sessionId = null
        if (remote != null && id != null) runCatching { remote.closeSession(id) }
      }
    }
    withContext(Dispatchers.Main) {
      val shouldUnbind = synchronized(stateLock) {
        val wasBound = bound
        bound = false
        service = null
        connectionResult = null
        wasBound
      }
      if (shouldUnbind) runCatching {
        Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
      }
    }
  }

  private suspend fun connect(): IShizukuShellService {
    service?.let { return it }
    return withContext(Dispatchers.Main) {
      check(!closed) { "Shizuku shell client is closed." }
      check(Shizuku.pingBinder()) {
        "Shizuku is not running. Start the Shizuku service and retry."
      }
      check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        "Shizuku permission is not granted. Grant the app permission in Shizuku, then retry."
      }

      val deferred: CompletableDeferred<IShizukuShellService>
      val shouldBind: Boolean
      synchronized(stateLock) {
        service?.let { return@withContext it }
        val existing = connectionResult
        if (existing != null && !existing.isCompleted) {
          deferred = existing
          shouldBind = false
        } else {
          deferred = CompletableDeferred()
          connectionResult = deferred
          shouldBind = !bound
        }
      }

      if (shouldBind) {
        try {
          Shizuku.bindUserService(userServiceArgs, serviceConnection)
          synchronized(stateLock) { bound = true }
        } catch (error: Exception) {
          synchronized(stateLock) {
            connectionResult = null
            bound = false
          }
          throw IllegalStateException("Could not bind Shizuku shell service: " +
            (error.message ?: error.javaClass.simpleName), error)
        }
      }

      try {
        withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
      } catch (error: TimeoutCancellationException) {
        throw IllegalStateException("Timed out waiting for the Shizuku shell service to connect.", error)
      }
    }
  }

  companion object {
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val DEFAULT_OUTPUT_LIMIT = 32 * 1_024
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val MIN_TIMEOUT_MS = 100L
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val MIN_OUTPUT_CHARS = 1_024
    private const val MAX_OUTPUT_CHARS = 128 * 1_024
  }
}

/** Structured result from one Android shell command. */
data class ShizukuShellResult(
  val stdout: String,
  val stderr: String,
  val exitCode: Int,
  val timedOut: Boolean,
  val outputTruncated: Boolean,
  val error: String? = null,
  val processMayStillBeRunning: Boolean = false,
) {
  val succeeded: Boolean
    get() = error == null && !timedOut && exitCode == 0 && !processMayStillBeRunning
}
