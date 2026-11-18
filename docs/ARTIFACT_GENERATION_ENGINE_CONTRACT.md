# Stage 10B — Shared artifact-generation contracts and approval boundary

**Status:** Contract, registries, typed result model, generic tool schema, and one-time approval UI implemented in PR #50. Runtime registration is paired with the first working generator in Stage 10C so the agent never advertises a format with no implementation.  
**Validation:** Unit tests added; not executed. No Gradle build or device check run.

## Model-facing envelope

The single future model-facing operation is `artifact_create`. It accepts one strict common envelope:

| Field | Meaning |
|---|---|
| `format` | `pdf`, `docx`, `xlsx`, `pptx`, `zip`, or `image` |
| `file_name` | Safe leaf filename with an extension matching the selected format |
| `destination_type` | Exactly one of `saf` or `shell` |
| `tree_uri` | Required for SAF; must originate from Gallery's document-tree grant flow |
| `parent_document_id` | Optional SAF destination folder ID from the same granted tree |
| `backend` | Required for shell: explicit `TERMUX` or `SHIZUKU`; TERMUX_RISH binary output is unavailable pending stdin-forwarding proof |
| `path` | Required for shell: absolute path whose leaf exactly matches `file_name` |
| `overwrite` | Optional boolean; defaults to false |
| `payload` | Format-specific JSON object validated by the selected generator |

The parser rejects unknown keys, mixed destination identities, unsupported formats/backends, unsafe leaf names, mismatched extensions, relative or dot-segment shell paths, and malformed destination fields. Common payload bounds are 128 KiB serialized JSON, depth 12, and 5,000 JSON nodes. Format adapters add their own schema and output-byte limits.

## Engine contracts

- `ArtifactGeneratorRegistry` enforces one generator per format.
- `ArtifactDestinationRegistry` enforces one writer per destination type (SAF or shell); backend selection remains explicit inside the shell destination.
- Each `ArtifactGenerator` declares its format and a positive maximum output size, validates its own typed payload, and writes to an OutputStream.
- Each `ArtifactDestinationAdapter` describes the exact destination and owns document creation, streaming, partial-output cleanup, overwrite handling, and finalization.
- SAF writes binary output directly through a persisted Gallery document-tree grant. Shell writes stage privately and stream to the explicitly selected identity: Termux uses bounded RUN_COMMAND stdin chunks; Shizuku uses a one-shot ParcelFileDescriptor pipe handled by the shell-UID UserService. Both verify destination byte count and SHA-256 before same-directory finalization. TERMUX_RISH is rejected for binary output until remote stdin forwarding is proven.
- The shared engine computes output byte count and SHA-256 while streaming, checks the returned MIME type against the selected format, requests approval before creating output, and enforces a 120-second operation deadline.
- Cancellation propagates to the destination adapter, which must clean partial output and report uncertain cleanup rather than claiming success. Termux chunk transfer attempts to remove its sibling `.part` file; Shizuku closes the PFD pipe and the UserService removes its staging file on incomplete transfer.
- Results are typed metadata only: format, filename, destination label, MIME type, byte count, SHA-256, success, and a concise message. Raw artifact bytes are never returned to Gemma.

## Approval boundary

Every create operation requests one-time approval showing:
- Exact format and filename
- Exact SAF folder or selected shell backend/path
- Estimated size when available
- Whether replacement was requested

Deny, dismiss, missing UI collector, or approval timeout fails closed. Remote image generation requires a separate disclosure/consent step in Stage 10H; ordinary artifact approval does not authorize prompt/source-image upload.

## Implementation sequencing

- Stage 10B provides contracts and approval infrastructure.
- Stage 10C adds the first working PDF generator and destination integration, then registers the single generic tool in the agent runtime.
- DOCX/XLSX/PPTX/ZIP/image adapters register against the same engine; they do not create separate model-facing tools.
- Tests are present but remain unexecuted until Stage 10A–10J onboarding is complete.
