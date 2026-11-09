# Agent runtime diagnostics

These diagnostics help distinguish model behavior from LiteRT-LM parsing and coordinator dispatch failures.

## Capture a real Agent Chat run

From PowerShell on Windows:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -v time -s LiteRtLmAgentModel:I NativeAgentRuntime:I
```

Leave Logcat running, open Agent Chat, start a fresh conversation, and send a harmless tool-use test such as:

`Use execute_shell to run exactly id with backend SHIZUKU. Actually invoke the registered tool.`

Stop Logcat with Ctrl+C after the response. The filter is limited to the two runtime diagnostic tags.

## What the records mean

- **Agent conversation created**: selected model, active accelerator, and constrained-decoding setting passed to conversation creation.
- **Conversation ready**: confirms manual tool calling is enabled and lists the actual tool names, parameter names, and required parameter names registered with LiteRT-LM.
- **Turn request**: reports whether `enable_thinking` was supplied to the model and how many tool-result messages are being sent back.
- **Response protocol**: reports content length, structured tool-call count and names, argument key names, response channel names and lengths, and whether native Gemma tool-call markers appeared in content or any channel.

The diagnostics intentionally do not log prompts, response text, thinking text, shell command values, or tool argument values.

## Interpreting a failed tool call

- `registeredTools` missing `execute_shell`: tool-provider registration/conversation construction problem.
- `thinkingRequested=not-provided`: the UI/runtime did not pass the thinking preference on this turn.
- `structuredToolCalls=0` with native markers: LiteRT-LM returned the model's native call syntax as text instead of a parsed tool call.
- `structuredToolCalls=0`, no markers, and a non-empty content body: the model returned ordinary text rather than invoking a tool.
- Channel-only output: the model returned channels but neither final content nor a structured tool call; the adapter fails closed.
- `structuredToolCalls>0`: the model-to-SDK parsing boundary succeeded. The next expected step is coordinator validation and, for shell execution, the explicit approval dialog.

A missing approval dialog by itself does not prove a transport failure. The protocol records identify whether the coordinator ever received a structured call. The shell tool remains manually dispatched through AgentCoordinator; LiteRT-LM automatic tool execution stays disabled.
