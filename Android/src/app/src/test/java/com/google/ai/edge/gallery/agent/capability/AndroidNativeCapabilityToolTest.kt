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

import com.google.ai.edge.gallery.data.AgentDownloadOperations
import com.google.ai.edge.gallery.data.AgentDownloadSnapshot
import com.google.ai.edge.gallery.tools.CapturePhotoToolAction
import com.google.ai.edge.gallery.tools.RequestPermissionToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeCapabilityToolTest {
  @Test
  fun androidControlSchemaKeepsEveryOperationAndSafetyCriticalArgumentGuidanceCompactly() {
    val schema = AndroidNativeCapabilityTool(adapter = FakeNativeCapabilityOperations()).parametersSchema
    val properties = schema.getAsJsonObject("properties")
    val operations = properties.getAsJsonObject("operation").getAsJsonArray("enum")
      .map { it.asString }

    assertTrue(operations.containsAll(listOf(
      "device_info", "bluetooth_gatt_write", "read_notifications", "read_call_log",
      "read_sms_inbox", "usb_read_descriptors", "capture_photo", "keystore_decrypt",
      "media_search", "media_play",
    )))
    assertTrue(operations.size >= 45)
    assertTrue(properties.getAsJsonObject("volume").get("description").asString.contains("selected stream"))
    assertTrue(properties.getAsJsonObject("duration_ms").has("description"))
    assertEquals(2, properties.getAsJsonObject("importance").get("minimum").asInt)
    assertEquals(4, properties.getAsJsonObject("importance").get("maximum").asInt)
    assertEquals(0, properties.getAsJsonObject("hour").get("minimum").asInt)
    assertEquals(23, properties.getAsJsonObject("hour").get("maximum").asInt)
    assertTrue(properties.getAsJsonObject("usb_handle").has("description"))
    assertTrue(properties.getAsJsonObject("phone_number").has("description"))
    assertTrue(properties.getAsJsonObject("sms_message").has("description"))
    assertTrue(properties.getAsJsonObject("media_handle").has("description"))
    assertTrue(schema.toString().length < 6_000)
  }

  @Test
  fun usbDescriptorReadRequiresExplicitOperationAndFreshHandle() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter = adapter)
    val handle = "usb_" + "c".repeat(32)

    val result = tool.execute(arguments("usb_read_descriptors").apply {
      addProperty("usb_handle", handle)
    })

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("vendorId"))
    assertTrue(adapter.discoveryCalls.contains("usb_read_descriptors:$handle"))
  }

  @Test
  fun readSmsInboxDelegatesToTheNativeAdapterForTheExplicitOperation() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter = adapter)

    val result = tool.execute(arguments("read_sms_inbox"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("Message preview"))
    assertTrue(result.content.contains("5550100"))
    assertTrue(adapter.discoveryCalls.contains("read_sms_inbox"))
  }

  @Test
  fun readCallLogDelegatesToTheNativeAdapterOnlyForTheExplicitOperation() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter = adapter)

    val result = tool.execute(arguments("read_call_log"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("MISSED"))
    assertTrue(result.content.contains("555-0100"))
    assertTrue(adapter.discoveryCalls.contains("read_call_log"))
  }

  @Test
  fun readNotificationsDelegatesOnlyWhenCapabilityIsAvailable() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter = adapter)

    val result = tool.execute(arguments("read_notifications"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("Chat preview"))
    assertTrue(adapter.discoveryCalls.contains("read_notifications"))
  }

  @Test
  fun readNotificationsFailsClosedWhenListenerAccessIsUnavailable() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      availability[AndroidCapability.NOTIFICATION_READ] = ProviderAvailability.PERMISSION_REQUIRED
    }
    val tool = AndroidNativeCapabilityTool(adapter = adapter)

    val result = tool.execute(arguments("read_notifications"))

    assertFalse(result.isSuccessful)
    assertTrue(result.content.contains("Notification-listener"))
    assertFalse(adapter.discoveryCalls.contains("read_notifications"))
  }

  @Test
  fun runtimePermissionRequesterReturnsOnlyTheSystemPermissionResult() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 1_000L)
    val responder = launch {
      val action = actions.receive() as RequestPermissionToolAction
      assertEquals("android.permission.BLUETOOTH_SCAN", action.permission)
      action.result.complete(true)
    }
    assertTrue(requester.request("android.permission.BLUETOOTH_SCAN"))
    responder.join()
  }

  @Test
  fun runtimePermissionRequesterFailsClosedWhenActionChannelIsClosed() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED).apply { close() }
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 1_000L)
    assertFalse(requester.request("android.permission.BLUETOOTH_CONNECT"))
  }

  @Test
  fun runtimePermissionRequesterTimesOutIfNoUiConsumerCanReceiveTheAction() = runBlocking {
    val actions = Channel<ToolAction>(capacity = 0)
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 10L)
    assertFalse(requester.request("android.permission.BLUETOOTH_CONNECT"))
  }

  @Test
  fun photoCaptureRequesterPassesTheGalleryOwnedUriAndReturnsTheCameraResult() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelNativePhotoCaptureRequester(actions, timeoutMs = 1_000L)
    val uri = "content://media/external/images/media/42"
    val responder = launch {
      val action = actions.receive() as CapturePhotoToolAction
      assertEquals(uri, action.contentUri)
      action.result.complete(true)
    }
    assertTrue(requester.capture(uri))
    responder.join()
  }

  @Test
  fun photoCaptureRequesterTimesOutWhenNoUiConsumerReceivesTheAction() = runBlocking {
    val actions = Channel<ToolAction>(capacity = 0)
    val requester = ChannelNativePhotoCaptureRequester(actions, timeoutMs = 10L)
    assertFalse(requester.capture("content://media/external/images/media/42"))
  }

  @Test
  fun speechPlaybackRequesterPassesExactTextAndReturnsUiResult() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelNativeSpeechPlaybackRequester(actions, timeoutMs = 1_000L)
    val expected = "A short spoken response."
    val responder = launch {
      val action = actions.receive() as com.google.ai.edge.gallery.tools.SpeakTextToolAction
      assertEquals(expected, action.text)
      action.result.complete(true)
    }

    assertTrue(requester.speak(expected))
    responder.join()
  }

  @Test
  fun speechPlaybackRequesterFailsClosedWhenUiDoesNotRespond() = runBlocking {
    val actions = Channel<ToolAction>(capacity = 0)
    val requester = ChannelNativeSpeechPlaybackRequester(actions, timeoutMs = 10L)
    assertFalse(requester.speak("No UI consumer"))
  }

  @Test
  fun speakTextRequiresExactApprovalAndUsesInjectedGalleryPlaybackRequester() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val expectedText = "Hello from Gallery."
    var spokenText: String? = null
    var approvedCapability: AndroidCapability? = null
    var approvalSummary: String? = null
    val requester = NativeSpeechPlaybackRequester { text ->
      spokenText = text
      true
    }
    val authorizer = NativeCapabilityAuthorizer { capability, summary ->
      approvedCapability = capability
      approvalSummary = summary
      true
    }
    val tool = AndroidNativeCapabilityTool(
      adapter = adapter,
      registry = CapabilityCatalog.createRegistry(),
      speechPlaybackRequester = requester,
      speechAuthorizer = authorizer,
    )

    val result = tool.execute(arguments("speak_text").apply {
      addProperty("speech_text", expectedText)
    })

    assertTrue(result.isSuccessful)
    assertEquals(expectedText, spokenText)
    assertEquals(AndroidCapability.TEXT_TO_SPEECH, approvedCapability)
    assertTrue(approvalSummary.orEmpty().contains(expectedText))
  }

  @Test
  fun speakTextDoesNotStartPlaybackWhenApprovalIsDenied() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    var playbackRequested = false
    val requester = NativeSpeechPlaybackRequester {
      playbackRequested = true
      true
    }
    val authorizer = NativeCapabilityAuthorizer { _, _ -> false }
    val tool = AndroidNativeCapabilityTool(
      adapter = adapter,
      registry = CapabilityCatalog.createRegistry(),
      speechPlaybackRequester = requester,
      speechAuthorizer = authorizer,
    )

    val result = tool.execute(arguments("speak_text").apply {
      addProperty("speech_text", "Do not speak this.")
    })

    assertFalse(result.isSuccessful)
    assertFalse(playbackRequested)
  }

  @Test
  fun downloadStatusReturnsBoundedSnapshotsWithoutRequestingApproval() = runBlocking {
    val downloads = FakeAgentDownloadOperations()
    val adapter = FakeNativeCapabilityOperations().apply {
      availability[AndroidCapability.DOWNLOAD_MANAGEMENT] = ProviderAvailability.UNSUPPORTED
    }
    var approvalRequested = false
    val tool = AndroidNativeCapabilityTool(
      adapter = adapter,
      downloadOperations = downloads,
      downloadAuthorizer = NativeCapabilityAuthorizer { _, _ ->
        approvalRequested = true
        true
      },
    )

    val providerStatus = tool.execute(arguments("status"))
    val result = tool.execute(arguments("download_status"))

    assertTrue(providerStatus.content.contains("DOWNLOAD_MANAGEMENT=AVAILABLE"))
    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("model=Gemma E2B"))
    assertTrue(result.content.contains("phase=RUNNING"))
    assertTrue(result.content.contains("download_handle=${downloads.snapshot.handle}"))
    assertFalse(result.content.contains("workerId"))
    assertFalse(approvalRequested)
  }

  @Test
  fun cancelDownloadRequiresFreshHandleAndExactOneTimeApproval() = runBlocking {
    val downloads = FakeAgentDownloadOperations()
    var approvedCapability: AndroidCapability? = null
    var approvalSummary = ""
    val tool = AndroidNativeCapabilityTool(
      adapter = FakeNativeCapabilityOperations(),
      downloadOperations = downloads,
      downloadAuthorizer = NativeCapabilityAuthorizer { capability, summary ->
        approvedCapability = capability
        approvalSummary = summary
        true
      },
    )

    val beforeStatus = tool.execute(arguments("cancel_download").apply {
      addProperty("download_handle", downloads.snapshot.handle)
    })
    assertFalse(beforeStatus.isSuccessful)
    assertTrue(downloads.cancelledHandles.isEmpty())

    tool.execute(arguments("download_status"))
    val result = tool.execute(arguments("cancel_download").apply {
      addProperty("download_handle", downloads.snapshot.handle)
    })

    assertTrue(result.isSuccessful)
    assertEquals(AndroidCapability.DOWNLOAD_MANAGEMENT, approvedCapability)
    assertTrue(approvalSummary.contains("Gemma E2B"))
    assertTrue(approvalSummary.contains("RUNNING"))
    assertEquals(listOf(downloads.snapshot.handle), downloads.cancelledHandles)
  }

  @Test
  fun cancelDownloadDoesNotReachRepositoryWhenApprovalIsDenied() = runBlocking {
    val downloads = FakeAgentDownloadOperations()
    val tool = AndroidNativeCapabilityTool(
      adapter = FakeNativeCapabilityOperations(),
      downloadOperations = downloads,
      downloadAuthorizer = NativeCapabilityAuthorizer { _, _ -> false },
    )

    tool.execute(arguments("download_status"))
    val result = tool.execute(arguments("cancel_download").apply {
      addProperty("download_handle", downloads.snapshot.handle)
    })

    assertFalse(result.isSuccessful)
    assertTrue(downloads.cancelledHandles.isEmpty())
  }

  @Test
  fun mutationOperationsReachTheirMatchingNativeAdapterMethods() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val volume = tool.execute(arguments("set_volume").apply {
      addProperty("stream", "MUSIC")
      addProperty("volume", 7)
    })
    val flashlight = tool.execute(arguments("set_flashlight").apply {
      addProperty("enabled", true)
    })
    val launch = tool.execute(arguments("launch_app").apply {
      addProperty("package_name", "org.example.app")
    })
    val url = tool.execute(arguments("open_url").apply {
      addProperty("url", "https://example.com")
    })
    val bonded = tool.execute(arguments("bluetooth_bonded_devices"))
    val bleScan = tool.execute(arguments("bluetooth_le_scan").apply { addProperty("duration_ms", 5_000) })
    val usb = tool.execute(arguments("usb_devices"))
    val audio = tool.execute(arguments("record_audio").apply { addProperty("duration_ms", 5_000) })
    val photo = tool.execute(arguments("capture_photo"))
    val wallpaper = tool.execute(arguments("set_wallpaper").apply {
      addProperty("content_uri", "content://media/external/images/media/42")
      addProperty("target", "SYSTEM")
    })

    assertTrue(volume.isSuccessful)
    assertTrue(flashlight.isSuccessful)
    assertTrue(launch.isSuccessful)
    assertTrue(url.isSuccessful)
    assertTrue(bonded.isSuccessful)
    assertTrue(bleScan.isSuccessful)
    assertTrue(usb.isSuccessful)
    assertTrue(audio.isSuccessful)
    assertTrue(photo.isSuccessful)
    assertTrue(wallpaper.isSuccessful)
    assertEquals("content://media/external/audio/media/84", audio.content)
    assertEquals("content://media/external/images/media/42", photo.content)
    assertEquals("set_wallpaper:content://media/external/images/media/42:SYSTEM", adapter.discoveryCalls.last())
    assertEquals(listOf("bluetooth_bonded_devices", "bluetooth_le_scan:5000", "usb_devices"), adapter.discoveryCalls.take(3))
    val invalidScan = tool.execute(arguments("bluetooth_le_scan").apply { addProperty("duration_ms", 10_001) })
    val invalidAudio = tool.execute(arguments("record_audio").apply { addProperty("duration_ms", 30_001) })
    assertFalse(invalidScan.isSuccessful)
    assertFalse(invalidAudio.isSuccessful)
    assertEquals(5, adapter.discoveryCalls.size)
    assertEquals(
      listOf(
        "set_volume:MUSIC:7",
        "set_flashlight:true",
        "launch_app:org.example.app",
        "open_url:https://example.com",
      ),
      adapter.mutations,
    )
  }

  @Test
  fun bluetoothGattInspectionDispatchesOnlyWithAddressAndBoundedTimeout() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val result = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("device_address", "AA:BB:CC:DD:EE:FF")
      addProperty("timeout_ms", 10_000)
    })
    val missingAddress = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("timeout_ms", 10_000)
    })
    val invalidTimeout = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("device_address", "AA:BB:CC:DD:EE:FF")
      addProperty("timeout_ms", 20_001)
    })
    val invalidAddress = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("device_address", "not-a-mac")
    })

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("AA:BB:CC:DD:EE:FF"))
    assertFalse(missingAddress.isSuccessful)
    assertFalse(invalidTimeout.isSuccessful)
    assertFalse(invalidAddress.isSuccessful)
    assertEquals(listOf("bluetooth_gatt_services:AA:BB:CC:DD:EE:FF:10000"), adapter.discoveryCalls)
  }

  @Test
  fun bluetoothGattReadWriteDispatchBoundedUuidAddressAndPayloadArguments() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)
    val address = "AA:BB:CC:DD:EE:FF"
    val service = "0000180f-0000-1000-8000-00805f9b34fb"
    val characteristic = "00002a19-0000-1000-8000-00805f9b34fb"

    val read = tool.execute(arguments("bluetooth_gatt_read").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("timeout_ms", 10_000)
    })
    val write = tool.execute(arguments("bluetooth_gatt_write").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("value_base64", "AQID")
    })
    val invalidUuid = tool.execute(arguments("bluetooth_gatt_read").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", "not-a-uuid")
      addProperty("characteristic_uuid", characteristic)
    })
    val invalidPayload = tool.execute(arguments("bluetooth_gatt_write").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("value_base64", "not-base64!")
    })
    val oversizedPayload = tool.execute(arguments("bluetooth_gatt_write").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("value_base64", java.util.Base64.getEncoder().encodeToString(ByteArray(513)))
    })

    assertTrue(read.isSuccessful)
    assertTrue(read.content.contains("AQID"))
    assertTrue(write.isSuccessful)
    assertFalse(invalidUuid.isSuccessful)
    assertFalse(invalidPayload.isSuccessful)
    assertFalse(oversizedPayload.isSuccessful)
    assertEquals(
      listOf(
        "bluetooth_gatt_read:$address:$service:$characteristic:10000",
        "bluetooth_gatt_write:$address:$service:$characteristic:AQID:15000",
      ),
      adapter.discoveryCalls,
    )
  }

  @Test
  fun contactsAndClipboardOperationsDispatchOnlyBoundedArguments() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val contacts = tool.execute(arguments("contacts_search").apply {
      addProperty("query", "Alex")
      addProperty("limit", 5)
    })
    val clipboardRead = tool.execute(arguments("clipboard_read"))
    val clipboardWrite = tool.execute(arguments("clipboard_write").apply {
      addProperty("text", "Meeting notes")
    })
    val invalidContacts = tool.execute(arguments("contacts_search").apply {
      addProperty("query", "   ")
    })
    val oversizedClipboard = tool.execute(arguments("clipboard_write").apply {
      addProperty("text", "x".repeat(2_049))
    })

    assertTrue(contacts.isSuccessful)
    assertTrue(contacts.content.contains("Alex Example"))
    assertTrue(clipboardRead.isSuccessful)
    assertTrue(clipboardRead.content.contains("Meeting notes"))
    assertTrue(clipboardWrite.isSuccessful)
    assertFalse(invalidContacts.isSuccessful)
    assertFalse(oversizedClipboard.isSuccessful)
    assertEquals(
      listOf("contacts_search:Alex:5", "clipboard_read", "clipboard_write:Meeting notes"),
      adapter.discoveryCalls,
    )
  }

  @Test
  fun telephonyOperationsDispatchThroughTheNativeAdapter() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val info = tool.execute(arguments("telephony_info"))
    val dial = tool.execute(arguments("dial_phone").apply {
      addProperty("phone_number", "+1 (555) 010-1234")
    })
    val sms = tool.execute(arguments("compose_sms").apply {
      addProperty("phone_number", "+1 555 010 1234")
      addProperty("sms_message", "I am running five minutes late.")
    })

    assertTrue(info.isSuccessful)
    assertTrue(info.content.contains("GSM"))
    assertTrue(dial.isSuccessful)
    assertTrue(sms.isSuccessful)
    assertEquals(
      listOf(
        "dial_phone:+1 (555) 010-1234",
        "compose_sms:+1 555 010 1234:I am running five minutes late.",
      ),
      adapter.mutations,
    )
  }

  @Test
  fun telephonyMutationCapabilitiesFailClosedWhenUnavailable() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      availability[AndroidCapability.PHONE_CALL_DIAL] = ProviderAvailability.UNSUPPORTED
      availability[AndroidCapability.SMS_COMPOSE] = ProviderAvailability.UNSUPPORTED
    }
    val tool = AndroidNativeCapabilityTool(adapter)

    val dial = tool.execute(arguments("dial_phone").apply { addProperty("phone_number", "5550101234") })
    val sms = tool.execute(arguments("compose_sms").apply {
      addProperty("phone_number", "5550101234")
      addProperty("sms_message", "Hello")
    })

    assertFalse(dial.isSuccessful)
    assertFalse(sms.isSuccessful)
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun biometricAndKeystoreOperationsDispatchThroughTheNativeAdapter() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val biometric = tool.execute(arguments("biometric_auth").apply {
      addProperty("reason", "Unlock my protected data")
    })
    val create = tool.execute(arguments("keystore_create").apply {
      addProperty("key_alias", "test_key")
    })
    val list = tool.execute(arguments("keystore_list"))
    val encrypt = tool.execute(arguments("keystore_encrypt").apply {
      addProperty("key_alias", "test_key")
      addProperty("plaintext", "secret")
    })
    val decrypt = tool.execute(arguments("keystore_decrypt").apply {
      addProperty("key_alias", "test_key")
      addProperty("iv_base64", "AAAAAAAAAAAAAAAA")
      addProperty("ciphertext_base64", "AAAAAAAAAAAAAAAAAAAAAA==")
    })
    val delete = tool.execute(arguments("keystore_delete").apply {
      addProperty("key_alias", "test_key")
    })

    assertTrue(biometric.isSuccessful)
    assertTrue(create.isSuccessful)
    assertTrue(list.isSuccessful)
    assertTrue(list.content.contains("test_key"))
    assertTrue(encrypt.isSuccessful)
    assertTrue(encrypt.content.contains("ciphertextBase64"))
    assertTrue(decrypt.isSuccessful)
    assertEquals("decrypted test data", decrypt.content)
    assertTrue(delete.isSuccessful)
    assertEquals(
      listOf("keystore_create:test_key", "keystore_delete:test_key"),
      adapter.mutations.filter { it.startsWith("keystore_") },
    )
  }

  @Test
  fun biometricAndKeystoreOperationsFailClosedWhenUnavailable() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      availability[AndroidCapability.BIOMETRIC_AUTH] = ProviderAvailability.UNSUPPORTED
      availability[AndroidCapability.KEYSTORE_OPERATION] = ProviderAvailability.UNSUPPORTED
    }
    val tool = AndroidNativeCapabilityTool(adapter)

    val biometric = tool.execute(arguments("biometric_auth").apply {
      addProperty("reason", "Unlock")
    })
    val create = tool.execute(arguments("keystore_create").apply {
      addProperty("key_alias", "test_key")
    })

    assertFalse(biometric.isSuccessful)
    assertFalse(create.isSuccessful)
    assertTrue(adapter.mutations.isEmpty())
    assertTrue(adapter.discoveryCalls.isEmpty())
  }

  @Test
  fun notificationOperationsDispatchThroughTheNativeAdapter() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val post = tool.execute(arguments("post_notification").apply {
      addProperty("title", "Test")
      addProperty("message", "A test notification")
    })
    val posted = tool.execute(arguments("list_posted_notifications"))
    val dismiss = tool.execute(arguments("cancel_posted_notification").apply {
      addProperty("notification_id", "42")
    })
    val schedule = tool.execute(arguments("schedule_notification").apply {
      addProperty("title", "Reminder")
      addProperty("message", "Check the oven")
      addProperty("hour", 19)
      addProperty("minute", 30)
      addProperty("year", 2030)
      addProperty("month", 5)
      addProperty("day", 6)
      addProperty("repeat_daily", false)
    })
    val list = tool.execute(arguments("list_scheduled_notifications"))
    val cancel = tool.execute(arguments("cancel_scheduled_notification").apply {
      addProperty("notification_id", "test-id")
    })
    val channels = tool.execute(arguments("list_notification_channels"))
    val createChannel = tool.execute(arguments("create_notification_channel").apply {
      addProperty("channel_id", "custom_reminders")
      addProperty("channel_name", "Custom reminders")
      addProperty("importance", 3)
    })

    val customPost = tool.execute(arguments("post_notification").apply {
      addProperty("title", "Custom")
      addProperty("message", "Use the custom channel")
      addProperty("channel_id", "custom_reminders")
    })

    assertTrue(post.isSuccessful)
    assertTrue(post.content.contains("ID"))
    assertTrue(posted.isSuccessful)
    assertTrue(posted.content.contains("42"))
    assertTrue(dismiss.isSuccessful)
    assertTrue(schedule.isSuccessful)
    assertTrue(list.isSuccessful)
    assertTrue(list.content.contains("test-id"))
    assertTrue(cancel.isSuccessful)
    assertTrue(channels.isSuccessful)
    assertTrue(channels.content.contains("Test"))
    assertTrue(createChannel.isSuccessful)
    assertTrue(customPost.isSuccessful)
    assertEquals(
      listOf(
        "post_notification:Test:A test notification:agent_skill_tasks_channel",
        "list_posted_notifications",
        "cancel_posted_notification:42",
        "schedule_notification:Reminder:19:30:2030:5:6:false",
        "list_scheduled_notifications",
        "cancel_scheduled_notification:test-id",
        "list_notification_channels",
        "create_notification_channel:custom_reminders:Custom reminders:3",
        "post_notification:Custom:Use the custom channel:custom_reminders",
      ),
      adapter.discoveryCalls,
    )
  }

  @Test
  fun notificationOperationsRejectInvalidOrIncompleteArgumentsBeforeDispatch() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val missingMessage = tool.execute(arguments("post_notification").apply {
      addProperty("title", "Test")
    })
    val partialDate = tool.execute(arguments("schedule_notification").apply {
      addProperty("title", "Reminder")
      addProperty("message", "Test")
      addProperty("hour", 9)
      addProperty("minute", 30)
      addProperty("year", 2030)
    })
    val invalidTime = tool.execute(arguments("schedule_notification").apply {
      addProperty("title", "Reminder")
      addProperty("message", "Test")
      addProperty("hour", 24)
      addProperty("minute", 30)
    })
    val invalidChannel = tool.execute(arguments("create_notification_channel").apply {
      addProperty("channel_id", "bad/id")
      addProperty("channel_name", "Invalid")
      addProperty("importance", 3)
    })

    assertFalse(missingMessage.isSuccessful)
    assertFalse(partialDate.isSuccessful)
    assertFalse(invalidTime.isSuccessful)
    assertFalse(invalidChannel.isSuccessful)
    assertTrue(adapter.discoveryCalls.isEmpty())
  }

  @Test
  fun systemCapabilityOperationsReachTheirMatchingAdapterMethods() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val wifiSettings = tool.execute(arguments("wifi_open_settings"))
    val devices = tool.execute(arguments("audio_devices"))
    val brightness = tool.execute(arguments("display_brightness"))
    val setBrightness = tool.execute(arguments("set_display_brightness").apply {
      addProperty("level", 120)
    })
    val vibration = tool.execute(arguments("vibrate").apply {
      addProperty("duration_ms", 250)
    })
    val wakeLock = tool.execute(arguments("set_wake_lock").apply {
      addProperty("held", true)
      addProperty("duration_ms", 30_000)
    })
    val wakeLockStatus = tool.execute(arguments("wake_lock_status"))
    val releaseWakeLock = tool.execute(arguments("set_wake_lock").apply {
      addProperty("held", false)
    })

    assertTrue(wifiSettings.isSuccessful)
    assertTrue(devices.isSuccessful)
    assertTrue(brightness.isSuccessful)
    assertTrue(setBrightness.isSuccessful)
    assertTrue(vibration.isSuccessful)
    assertTrue(wakeLock.isSuccessful)
    assertTrue(wakeLockStatus.isSuccessful)
    assertTrue(releaseWakeLock.isSuccessful)
    assertEquals(
      listOf(
        "wifi_open_settings",
        "set_display_brightness:120",
        "vibrate:250",
        "set_wake_lock:true:30000",
        "set_wake_lock:false:0",
      ),
      adapter.mutations,
    )
  }

  @Test
  fun invalidSystemMutationArgumentsAreRejectedBeforeAdapterDispatch() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val missingBrightness = tool.execute(arguments("set_display_brightness"))
    val missingVibration = tool.execute(arguments("vibrate"))
    val missingWakeLockState = tool.execute(arguments("set_wake_lock"))
    val missingWakeLockDuration = tool.execute(arguments("set_wake_lock").apply {
      addProperty("held", true)
    })

    assertFalse(missingBrightness.isSuccessful)
    assertFalse(missingVibration.isSuccessful)
    assertFalse(missingWakeLockState.isSuccessful)
    assertFalse(missingWakeLockDuration.isSuccessful)
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun unavailableMutationCapabilityNeverReachesAdapter() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      availability[AndroidCapability.FLASHLIGHT_CONTROL] = ProviderAvailability.PERMISSION_REQUIRED
    }
    val tool = AndroidNativeCapabilityTool(adapter)

    val result = tool.execute(arguments("set_flashlight").apply {
      addProperty("enabled", true)
    })

    assertFalse(result.isSuccessful)
    assertFalse(result.content.isBlank())
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun invalidMutationArgumentsAreRejectedBeforeAdapterDispatch() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val missingVolume = tool.execute(arguments("set_volume").apply {
      addProperty("stream", "MUSIC")
    })
    val invalidStream = tool.execute(arguments("set_volume").apply {
      addProperty("stream", "NOT_A_STREAM")
      addProperty("volume", 4)
    })
    val missingFlashlightState = tool.execute(arguments("set_flashlight"))

    assertFalse(missingVolume.isSuccessful)
    assertFalse(invalidStream.isSuccessful)
    assertFalse(missingFlashlightState.isSuccessful)
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun adapterFailureIsPreservedAsFailedToolResult() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      actionResult = NativeCapabilityActionResult(false, "Android denied the operation.")
    }
    val tool = AndroidNativeCapabilityTool(adapter)

    val result = tool.execute(arguments("set_volume").apply {
      addProperty("volume", 5)
    })

    assertFalse(result.isSuccessful)
    assertEquals("Android denied the operation.", result.content)
  }

  private fun arguments(operation: String) = JsonObject().apply {
    addProperty("operation", operation)
  }

  private class FakeAgentDownloadOperations : AgentDownloadOperations {
    val snapshot = AgentDownloadSnapshot(
      handle = "dl_" + "a".repeat(32),
      modelLabel = "Gemma E2B",
      extraDataOnly = false,
      phase = "RUNNING",
      receivedBytes = 512L,
      totalBytes = 1_024L,
      bytesPerSecond = 128L,
      remainingMs = 4_000L,
    )
    val cancelledHandles = mutableListOf<String>()

    override fun getAgentDownloadSnapshots(onResult: (List<AgentDownloadSnapshot>) -> Unit) {
      onResult(listOf(snapshot))
    }

    override fun cancelAgentDownload(handle: String, onResult: (Boolean) -> Unit) {
      cancelledHandles += handle
      onResult(handle == snapshot.handle)
    }
  }


  @Test
  fun permissionRevocationIsRecheckedOnEveryToolExecution() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter = adapter)

    val first = tool.execute(arguments("read_notifications"))
    assertTrue(first.isSuccessful)
    assertEquals(1, adapter.discoveryCalls.count { it == "read_notifications" })

    adapter.availability[AndroidCapability.NOTIFICATION_READ] =
      ProviderAvailability.PERMISSION_REQUIRED
    val afterRevocation = tool.execute(arguments("read_notifications"))

    assertFalse(afterRevocation.isSuccessful)
    assertTrue(afterRevocation.content.contains("Notification-listener"))
    assertEquals(1, adapter.discoveryCalls.count { it == "read_notifications" })
  }

  @Test
  fun providerUnavailabilityIsRecheckedBeforeEveryOperation() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter = adapter)

    val first = tool.execute(arguments("read_notifications"))
    assertTrue(first.isSuccessful)

    adapter.availability[AndroidCapability.NOTIFICATION_READ] =
      ProviderAvailability.SERVICE_STOPPED
    val afterProviderStops = tool.execute(arguments("read_notifications"))

    assertFalse(afterProviderStops.isSuccessful)
    assertEquals(1, adapter.discoveryCalls.count { it == "read_notifications" })
  }

  @Test
  fun runtimePermissionRequestCancellationPropagatesAndCompletesAsDenied() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 10_000L)
    val request = async {
      requester.request("android.permission.BLUETOOTH_CONNECT")
    }
    val action = actions.receive() as RequestPermissionToolAction
    request.cancel()

    try {
      request.await()
      throw AssertionError("Expected permission-request cancellation to propagate.")
    } catch (_: kotlinx.coroutines.CancellationException) {
      assertTrue(action.result.isCompleted)
      assertFalse(action.result.await())
    }
    actions.close()
    Unit
  }

  private class FakeNativeCapabilityOperations : NativeCapabilityOperations {
    override val descriptor =
      CapabilityCatalog.providers.first { it.id == CapabilityProviderId.ANDROID_NATIVE }
    val availability = descriptor.capabilities.associateWith {
      ProviderAvailability.AVAILABLE
    }.toMutableMap()
    val mutations = mutableListOf<String>()
    val discoveryCalls = mutableListOf<String>()
    var actionResult = NativeCapabilityActionResult(true, "Action completed.")

    override suspend fun inspect() = CapabilityProviderObservation(
      providerId = descriptor.id,
      availability = ProviderAvailability.AVAILABLE,
      detail = "Fake native provider.",
      capabilityAvailability = availability.toMap(),
    )

    override fun getDeviceInfo() = NativeDeviceInfo(
      manufacturer = "test",
      brand = "test",
      model = "test",
      device = "test",
      androidRelease = "test",
      sdkInt = 1,
      securityPatch = null,
    )

    override fun getTelephonyInfo() = NativeTelephonyInfo(
      hasTelephonyFeature = true,
      phoneType = "GSM",
      simState = "READY",
    )

    override suspend fun readCallLog(): NativeCallLogSnapshot {
      discoveryCalls += "read_call_log"
      return NativeCallLogSnapshot(
        entries = listOf(NativeCallLogEntry("Alex Example", "555-0100", "MISSED", 1_800_000_000_000L, 0L)),
        truncated = false,
      )
    }

    override suspend fun readSmsInbox(): NativeSmsInboxSnapshot {
      discoveryCalls += "read_sms_inbox"
      return NativeSmsInboxSnapshot(
        entries = listOf(NativeSmsInboxEntry("5550100", "Message preview", 1_800_000_000_000L, false)),
        truncated = false,
      )
    }

    override fun getBatteryStatus() = NativeBatteryStatus(
      percent = 50,
      state = "DISCHARGING",
      charging = false,
      plugged = "NOT_PLUGGED",
      temperatureCelsius = 25f,
      voltageMillivolts = 3800,
    )

    override fun getVolumeStatus(stream: NativeAudioStream) =
      NativeVolumeStatus(stream, 5, 15)

    override fun getAudioDevices() = listOf(
      NativeAudioDevice(1, "BUILTIN_SPEAKER", "Speaker", source = false, sink = true)
    )

    override fun getDisplayBrightness() =
      NativeDisplayBrightness(level = 120, mode = "MANUAL", canModifySystemSettings = true)

    override suspend fun setDisplayBrightness(level: Int): NativeCapabilityActionResult {
      mutations += "set_display_brightness:$level"
      return actionResult
    }

    override suspend fun vibrate(durationMs: Long): NativeCapabilityActionResult {
      mutations += "vibrate:$durationMs"
      return actionResult
    }

    override fun isWakeLockHeld() = false

    override suspend fun setWakeLock(
      held: Boolean,
      durationMs: Long,
    ): NativeCapabilityActionResult {
      mutations += "set_wake_lock:$held:$durationMs"
      return actionResult
    }

    override suspend fun setVolume(
      stream: NativeAudioStream,
      volume: Int,
    ): NativeCapabilityActionResult {
      mutations += "set_volume:${stream.name}:$volume"
      return actionResult
    }

    override suspend fun setFlashlight(enabled: Boolean): NativeCapabilityActionResult {
      mutations += "set_flashlight:$enabled"
      return actionResult
    }

    override fun listLaunchableApps() = emptyList<NativeLaunchableApp>()

    override suspend fun launchApp(packageName: String): NativeCapabilityActionResult {
      mutations += "launch_app:$packageName"
      return actionResult
    }

    override suspend fun openUrl(url: String): NativeCapabilityActionResult {
      mutations += "open_url:$url"
      return actionResult
    }

    override suspend fun openWifiSettings(): NativeCapabilityActionResult {
      mutations += "wifi_open_settings"
      return actionResult
    }

    override suspend fun dialPhoneNumber(phoneNumber: String): NativeCapabilityActionResult {
      mutations += "dial_phone:$phoneNumber"
      return actionResult
    }

    override suspend fun composeSms(phoneNumber: String, message: String): NativeCapabilityActionResult {
      mutations += "compose_sms:$phoneNumber:$message"
      return actionResult
    }

    override suspend fun authenticateBiometric(reason: String): Boolean {
      discoveryCalls += "biometric_auth:$reason"
      return true
    }

    override suspend fun createKeystoreKey(alias: String): NativeCapabilityActionResult {
      mutations += "keystore_create:$alias"
      return actionResult
    }

    override suspend fun listKeystoreKeys(): List<String> {
      discoveryCalls += "keystore_list"
      return listOf("test_key")
    }

    override suspend fun deleteKeystoreKey(alias: String): NativeCapabilityActionResult {
      mutations += "keystore_delete:$alias"
      return actionResult
    }

    override suspend fun encryptKeystoreData(alias: String, plaintext: String) =
      NativeKeystoreCiphertext(alias, "aXZfYmFzZTY0", "Y2lwaGVydGV4dA==")

    override suspend fun decryptKeystoreData(alias: String, ivBase64: String, ciphertextBase64: String) =
      "decrypted test data"
    override suspend fun getBluetoothBondedDevices(): List<NativeBluetoothDevice> {
      discoveryCalls += "bluetooth_bonded_devices"
      return emptyList()
    }
    override suspend fun scanBluetoothLe(durationMs: Long): List<NativeBluetoothDevice> {
      discoveryCalls += "bluetooth_le_scan:$durationMs"
      return emptyList()
    }
    override suspend fun inspectBluetoothGatt(
      deviceAddress: String,
      timeoutMs: Long,
    ): NativeBluetoothGattInspection {
      discoveryCalls += "bluetooth_gatt_services:$deviceAddress:$timeoutMs"
      return NativeBluetoothGattInspection(
        deviceAddress = deviceAddress,
        services = listOf(NativeBluetoothGattService("180F", "PRIMARY", emptyList())),
        totalCharacteristics = 0,
        truncated = false,
      )
    }

    override suspend fun readBluetoothGattCharacteristic(
      deviceAddress: String,
      serviceUuid: String,
      characteristicUuid: String,
      timeoutMs: Long,
    ): NativeBluetoothGattValue {
      discoveryCalls += "bluetooth_gatt_read:$deviceAddress:$serviceUuid:$characteristicUuid:$timeoutMs"
      return NativeBluetoothGattValue(deviceAddress, serviceUuid, characteristicUuid, "AQID", 3)
    }

    override suspend fun writeBluetoothGattCharacteristic(
      deviceAddress: String,
      serviceUuid: String,
      characteristicUuid: String,
      valueBase64: String,
      timeoutMs: Long,
    ): NativeCapabilityActionResult {
      discoveryCalls += "bluetooth_gatt_write:$deviceAddress:$serviceUuid:$characteristicUuid:$valueBase64:$timeoutMs"
      return actionResult
    }

    override suspend fun searchContacts(query: String, limit: Int): List<NativeContactSummary> {
      discoveryCalls += "contacts_search:$query:$limit"
      return listOf(NativeContactSummary("Alex Example", listOf("555-0100"), listOf("alex@example.com")))
    }

    override suspend fun readClipboard(): NativeClipboardContent {
      discoveryCalls += "clipboard_read"
      return NativeClipboardContent("Meeting notes", hasText = true, itemCount = 1, truncated = false)
    }

    override suspend fun writeClipboard(text: String): NativeCapabilityActionResult {
      discoveryCalls += "clipboard_write:$text"
      return actionResult
    }

    override suspend fun getUsbDevices(): List<NativeUsbDevice> {
      discoveryCalls += "usb_devices"
      return emptyList()
    }

    override suspend fun readUsbDescriptors(handle: String): NativeUsbDescriptorSnapshot {
      discoveryCalls += "usb_read_descriptors:$handle"
      return NativeUsbDescriptorSnapshot(
        vendorId = 0x1234,
        productId = 0x5678,
        descriptorBytes = 16,
        descriptors = listOf(NativeUsbDescriptor(9, 4, interfaceNumber = 1)),
        truncated = false,
      )
    }

    override suspend fun postNotification(
      title: String,
      message: String,
      channelId: String,
    ): NativeCapabilityActionResult {
      discoveryCalls += "post_notification:$title:$message:$channelId"
      return NativeCapabilityActionResult(true, "Notification posted with ID 42.")
    }

    override suspend fun readOtherNotifications(): NativeOtherNotificationList {
      discoveryCalls += "read_notifications"
      return NativeOtherNotificationList(
        notifications = listOf(
          NativeOtherNotification("Messages", "New message", "Chat preview", 1_800_000_000_000L)
        ),
        truncated = false,
      )
    }

    override fun listPostedNotifications(): List<NativePostedNotification> {
      discoveryCalls += "list_posted_notifications"
      return listOf(NativePostedNotification("42", "Test", "A test notification", 1_800_000_000_000L))
    }

    override suspend fun cancelPostedNotification(id: String): NativeCapabilityActionResult {
      discoveryCalls += "cancel_posted_notification:$id"
      return actionResult
    }

    override suspend fun scheduleNotification(
      title: String,
      message: String,
      hour: Int,
      minute: Int,
      year: Int?,
      month: Int?,
      day: Int?,
      repeatDaily: Boolean,
    ): NativeCapabilityActionResult {
      discoveryCalls += "schedule_notification:$title:$hour:$minute:$year:$month:$day:$repeatDaily"
      return actionResult
    }

    override suspend fun listScheduledNotifications(): List<NativeScheduledNotification> {
      discoveryCalls += "list_scheduled_notifications"
      return listOf(NativeScheduledNotification("test-id", "Reminder", "Do the thing", 9, 30, null, null, null, false))
    }

    override suspend fun cancelScheduledNotification(id: String): NativeCapabilityActionResult {
      discoveryCalls += "cancel_scheduled_notification:$id"
      return actionResult
    }

    override fun listNotificationChannels(): List<NativeNotificationChannel> {
      discoveryCalls += "list_notification_channels"
      return listOf(NativeNotificationChannel("test", "Test", 3))
    }

    override suspend fun createNotificationChannel(
      id: String,
      name: String,
      importance: Int,
    ): NativeCapabilityActionResult {
      discoveryCalls += "create_notification_channel:$id:$name:$importance"
      return actionResult
    }
    override suspend fun recordAudio(durationMs: Long): String {
      discoveryCalls += "record_audio:$durationMs"
      return "content://media/external/audio/media/84"
    }
    override suspend fun capturePhoto(): String = "content://media/external/images/media/42"
    override suspend fun setWallpaper(contentUri: String, target: String): NativeCapabilityActionResult {
      discoveryCalls += "set_wallpaper:$contentUri:$target"
      return actionResult
    }
  }
}
