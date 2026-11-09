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

package com.google.ai.edge.gallery.agent.capability

import com.google.ai.edge.gallery.tools.AskNativeCapabilityPermissionAction
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNativeCapabilityAuthorizerTest {
  @Test
  fun approvalUsesExistingActionChannelAndExactOperationSummary() = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(Channel.UNLIMITED)
    val authorizer = ChannelNativeCapabilityAuthorizer(channel)
    val request = async {
      authorizer.authorize(
        AndroidCapability.VOLUME_CONTROL,
        "Set music volume to 7 of 15",
      )
    }

    val action = channel.receive() as AskNativeCapabilityPermissionAction
    assertEquals("VOLUME_CONTROL", action.capability)
    assertEquals("Set music volume to 7 of 15", action.summary)
    action.result.complete(true)

    assertTrue(request.await())
    channel.close()
  }

  @Test
  fun deniedApprovalReturnsFalse() = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(Channel.UNLIMITED)
    val authorizer = ChannelNativeCapabilityAuthorizer(channel)
    val request = async {
      authorizer.authorize(AndroidCapability.OPEN_URL, "Open https://example.com")
    }

    val action = channel.receive() as AskNativeCapabilityPermissionAction
    action.result.complete(false)

    assertFalse(request.await())
    channel.close()
  }

  @Test
  fun closedActionChannelFailsClosed() = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(Channel.UNLIMITED)
    channel.close()
    val authorizer = ChannelNativeCapabilityAuthorizer(channel, approvalTimeoutMs = 10)

    assertFalse(authorizer.authorize(AndroidCapability.APP_LAUNCH, "Launch app"))
  }
}
