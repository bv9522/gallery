# Model-Independent Authoritative Tool-Result Presentation Contract

**Status:** Stage 2 contract definition. This document defines the data and behavior boundary for Solution 3. It does not implement the Stage 3 event stream or chat UI.

## 1. Product rule

A tool result is runtime evidence, not model-authored prose.

The user-facing presentation MUST obtain the result from the retained authoritative runtime record. It MUST NOT reconstruct, transcribe, summarize, parse back out of, or otherwise depend on Gemma's response. Gemma may comment on the result in a separate, explicitly model-attributed region, or provide no commentary. Incorrect, contradictory, malformed, empty, refused, or unavailable model output MUST NOT alter or suppress an already retained tool result.

The model-facing tool message and the user-facing authoritative presentation are separate consumers of one execution. The UI is not an executor and does not request a second tool call to recover display data.

## 2. Existing source of truth and ownership

- `AgentCoordinator` remains the only component that dispatches tools and owns approval/execution ordering.
- `AgentRunResult.authoritativeToolResults` is the per-run collection of authoritative results.
- `AuthoritativeToolResult` is the existing retained source record. Its `toolCallId`, `toolName`, `succeeded`, `rawContent`, and optional `structuredValue` are reused; Stage 3 must not introduce a parallel result store or duplicate these fields in a second source-of-truth model.
- `rawContent` means the exact text retained after the existing result-budget operation. A bounded result is authoritative only for the retained content; omitted content must never be implied to be present.
- `structuredValue` is a typed side channel for deterministic fact rendering. It is not a substitute for the raw-output view and must not be serialized back into text to recreate raw output.
- `AuthoritativeToolResultRenderer` and the existing ARL response assembly remain intact. The transaction view complements ARL facts; it does not replace them.

## 3. Transaction identity and ordering

Each presentation item represents one tool invocation/result pair.

Its stable correlation identity is the tuple:

1. conversation/session identity;
2. originating user-turn identity;
3. model-provided tool-call ID.

The tool name is displayed as descriptive metadata, not used as the sole identity. A UI list index, timestamp, or tool name alone is not a stable identity. Preserve the coordinator's execution order for sequential and batched calls. Multiple calls in one batch remain distinct even when they use the same tool. Duplicate/replayed IDs must not cause a second execution or cause one result to overwrite another; execution deduplication remains a coordinator concern.

The current `AuthoritativeToolResult` already supplies the tool-call ID and tool name. The Stage 3 adapter must join it to the owning conversation and user turn using existing runtime/request identity; if a stable turn ID is not currently exposed at the UI boundary, add only a correlation identifier, not another execution/result store.

## 4. Outcome semantics

The presentation must expose a typed outcome, never infer success from friendly wording or model commentary.

| Outcome | Meaning | Required presentation behavior |
|---|---|---|
| `SUCCEEDED` | Provider operation completed successfully | Show success state and the retained result |
| `FAILED` | Operation ran or was attempted but failed | Show failure state and available error/result details |
| `DENIED` | User or policy did not authorize execution | Show denied state; do not imply the provider ran |
| `CANCELLED` | The operation was cancelled | Show cancelled only when a cancellation result/event is actually retained; do not fabricate output |
| `TIMED_OUT` | The operation exceeded its deadline | Show timeout explicitly, including any known process-cleanup uncertainty |
| `UNAVAILABLE` | Required provider/backend/capability was unavailable | Show unavailable distinctly from execution failure or denial |

The existing `succeeded` Boolean remains the ARL compatibility signal for fact eligibility. It is not rich enough to distinguish all presentation outcomes. Stage 3 must carry typed outcome metadata from the result producer/runtime boundary rather than classify arbitrary strings such as “denied” or “timed out.” If a cancelled coroutine is rolled back and no result is retained, the UI may show the turn's cancellation state but must not invent a completed tool-result transaction.

## 5. Generic result fields

The presentation adapter consumes the existing authoritative record and any typed, producer-supplied execution metadata. It must expose, when applicable:

- tool name and invocation identity;
- typed outcome;
- bounded raw content, including the empty-string case;
- structured facts only through the existing ARL path;
- explicit truncation/omission state;
- optional backend/provider identity and error detail;
- execution order.

The adapter must not derive metadata from Gemma's final response. A missing optional field remains unknown/unavailable; it is not guessed.

## 6. Shell result fields

For shell execution, the presentation contract requires independently addressable fields:

- backend identity (`TERMUX`, `SHIZUKU`, or `TERMUX_RISH`);
- exit code;
- timeout state;
- output-truncated state;
- stdout;
- stderr;
- execution error, if present;
- process-may-still-be-running state, if present.

Stdout and stderr must remain separate and preserve every retained character, including leading/trailing whitespace, blank lines, line endings, delimiters, Unicode, and character order. Do not call `trim`, `trimEnd`, normalize line endings, split/rejoin lines, or route raw streams through a model-generated summary before display. Empty stdout and stderr are meaningful values and should be represented as empty, not silently omitted from the typed record.

**Known implementation gap from Stage 1 audit:** `UnifiedShellExecution.kt` currently applies `trimEnd()` to stdout/stderr and `.trim()` to the assembled tool response before `AgentCoordinator` captures it. Therefore today's `AuthoritativeToolResult.rawContent` is authoritative for the post-normalization tool content, but is not proof of byte/character-exact original process streams. Stage 3 must source the shell display fields from the structured `ShellExecutionResult` before this formatting step. The model-facing textual protocol may keep a bounded representation, but it must not be the source for the raw stream view. This is a narrowly scoped fidelity fix, not a general compatibility rewrite.

## 7. Budget and fidelity

- Respect the existing per-tool and per-turn result budgets. Current defaults are 6,000 characters per tool and 12,000 characters per turn.
- The user-facing result must show the same retained bounded payload that the authoritative record owns. Do not create an unbounded duplicate to make the display look complete.
- When the budget truncates or omits content, display the explicit existing truncation marker/state and the known omission information. Never claim that the displayed prefix/suffix is the complete process output.
- Shell stdout/stderr must share a documented bounded-output policy. Preserve their separation and exact retained characters; the UI must not independently truncate one stream while continuing to label it complete.
- Copy must copy the exact displayed authoritative bounded source value, not rendered model commentary or lossy formatted text.
- Long output may be collapsed visually, but collapse is presentation-only: it must not mutate the retained payload. Expansion reads the same bounded source.

## 8. Model commentary separation

- Model commentary is a separate assistant message/region with explicit model attribution.
- It may explain, interpret, or answer questions about the result; it may also be empty or absent.
- It must never be merged into the raw-output region, used to fill missing output, or treated as a replacement for source fields.
- Contradictions remain visible as contradictions; neither the raw result nor the commentary silently overwrites the other.
- Model failure after a successful tool call must not remove the already retained result from the run presentation.

## 9. Privacy and lifecycle

- Apply each tool's existing sensitivity classification and approval/privacy rules before exposing retained data in the UI. The presentation layer must not broaden access to contacts, messages, notifications, location, screenshots, credentials, or other sensitive payloads.
- Do not add raw output to persistent logs or diagnostics merely to support the UI. Boundary diagnostics should use safe lengths/hashes and only the minimum metadata required.
- Avoid retaining a second copy of large output. UI state should reference or carry the bounded result through the existing conversation lifecycle.
- Preserve execution order and transaction identity across recomposition and supported conversation restoration. Do not replay tool side effects to rebuild presentation.
- Copy is an explicit user action and copies only the bounded payload already authorized for display.

## 10. Stage 2 contract tests and Stage 3 acceptance

Stage 2 regression tests establish that:
- exact bounded whitespace, newlines, delimiters, and character order survive coordinator capture;
- tool-call ID and tool name remain associated with the captured output;
- deliberately incorrect model commentary cannot mutate the retained raw output;
- an empty model final response does not make a retained tool result disappear;
- existing structured-value, failed-result, budget, and execution-order ARL tests remain unchanged and passing.

Stage 3 must additionally test the UI/event path for success, failure, denial, cancellation, timeout, unavailable provider, empty streams, stderr-only, mixed streams, truncation, multiple calls, long-output expansion, exact copy, missing/incorrect model commentary, and conversation recreation without duplicate execution. Boundary checks must compare safe length/hash fingerprints at process capture, structured result retention, model handoff, and displayed raw view. The original five ARL milestones remain independently governed by their existing evidence.

## 11. Explicit Stage 2 non-goals

- No new executor, dispatch route, approval authority, backend fallback, permission flow, or result store.
- No Stage 3 `AgentEvent` wiring, ViewModel changes, chat message type, or UI card.
- No model prompt patch or model-specific workaround.
- No rewrite of ARL capture, structured facts, provenance, response validation, or response assembly.
- No broad shell transport compatibility changes. The observed `trimEnd`/`trim` normalization is recorded for a focused Stage 3 fidelity correction with regression coverage.
