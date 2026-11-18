# Stage 10I — Binary artifact output through SAF and shell identities

**Status:** SAF, Termux, and Shizuku output adapters are implemented in PR #58. TERMUX_RISH remains disabled until end-to-end stdin forwarding is proven.  
**Validation:** Shell identity/path tests added but not executed. No Gradle tests, build, or device acceptance run.

## Shared output contract

The engine's artifact producer is now suspendable, allowing streaming destinations to apply backpressure and cancellation. One ShellArtifactDestinationAdapter owns shell destination selection; it routes only to the exact backend named in the request and has no fallback.

The artifact approval dialog shows the exact filename, destination identity/path, and overwrite behavior. Existing targets are refused; overwrite requests fail closed. A read-only preflight verifies the selected parent is already present/writable and the target is absent before approval. The destination is rechecked during the actual write.

## Termux binary transport

- Uses Termux RUN_COMMAND's stdin extra; requires Termux 0.109.0 or newer.
- Raw bytes are streamed through a bounded pipe, encoded as independent 128 KiB base64 chunks (encoded chunk ceiling 180,000 characters), and sent to base64 -d via stdin—not command arguments.
- A same-directory temporary file is created with restrictive umask and no-clobber creation.
- Required utilities are checked before transfer. Each chunk is bounded by a timeout command.
- After transfer, size and SHA-256 are checked, filesystem sync is requested, and mv -n publishes without replacing an existing target. The final target is verified; temporary cleanup is confirmed or reported.
- If stdin extras are ignored/unsupported, the size/hash verification fails and the temporary output is cleaned rather than publishing corrupt bytes.

## Shizuku binary transport

- Adds a dedicated AIDL writeBinaryFile method receiving a ParcelFileDescriptor read end; the app writes through the matching pipe.
- Shizuku UserService runs under the selected shell identity, streams into a same-directory temporary file, enforces the 100 MiB transport ceiling and operation deadline, computes SHA-256, verifies MIME/extension and file signature, and syncs the temporary file.
- A no-clobber target reservation followed by same-directory atomic rename publishes the complete file. Existing targets are never replaced.
- On error, the service attempts to remove the reservation and temporary file and reports uncertain cleanup.
- The service does not pass artifact bytes through shell command text or Binder Bundle payloads.

## Process-death caveat

Normal failures and cancellation attempt cleanup and report uncertainty. Abrupt process/service death cannot run that cleanup: Termux may leave a hidden .android-mcp-*.part file; Shizuku may leave a hidden temporary file or, in the narrow interval after no-clobber reservation and before rename, a zero-byte target placeholder. Stage 10J must exercise these windows and add safe recovery/cleanup without deleting another active transfer or user file. Until then, do not claim process-death cleanup is guaranteed.

## Backend identity policy

The model-facing schema currently exposes only TERMUX and SHIZUKU for shell binary output. TERMUX_RISH is intentionally not registered: the existing Rish execution path uses a command-only Shizuku AIDL method, and stdin forwarding to its remote shell has not been proven. It remains unavailable until Stage 10J performs an end-to-end device test; no fallback to direct Shizuku is permitted.

## Remaining validation

- Termux stdin extra/version compatibility, chunk boundaries, large-file latency, timeout/cancellation, and cleanup.
- Shizuku PFD transfer, service death, descriptor closure, shell identity/path permissions, atomic rename and cleanup.
- Filesystems that reject atomic rename or target reservation must fail closed; no overwrite fallback.
- Rish stdin-forwarding proof; enable only if actual bytes reach the remote shell correctly.
- Full Stage 10J resource, security, and S21 acceptance.
