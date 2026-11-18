# Stage 10G — ZIP creation and guarded SAF extraction

**Status:** ZIP creation and extraction core implemented in PRs #55–#56.  
**Validation:** ZIP creation and pure extraction-path tests added but not executed. No Gradle tests, build, or device acceptance run.

## ZIP creation

The generic `artifact_create` tool supports `format=zip`, `operation=create` (or omitted), and a payload `entries` array of `{path, content}` UTF-8 text files. Limits: 100 entries, 1 MiB per entry, 5 MiB total uncompressed text, 240-character paths, and 20 MiB output. Absolute/drive/backslash/control/dot/traversal/reserved-Windows paths, case-insensitive duplicates, and file/directory prefix collisions are rejected.

## ZIP extraction

The same model-facing tool supports `format=zip`, `operation=extract`, and:
- `source_tree_uri`: persisted Gallery SAF tree URI returned by `document_tree` grants
- `source_document_id`: document ID for a ZIP file returned by `document_tree` list operations

The common request's SAF destination identifies the separate destination tree and optional parent folder. The source must be inside the selected read-granted source tree; the destination must be inside a persisted read/write-granted destination tree.

### Two-pass safety flow

1. Preflight reads the selected archive without writing output. It validates entry names and path collisions, counts entries and expanded bytes, checks per-file limits and known compression ratios, and hashes the compressed archive.
2. Gallery checks every target path against the destination folder and requests one-time approval showing the archive, entry count, expanded size, and destination.
3. Extraction reopens the archive, verifies the same entry sequence/paths/sizes and the compressed-archive SHA-256, then streams regular files and directories into SAF.
4. Any failure/cancellation attempts reverse-order deletion of created files/folders. Cleanup failures are reported; partial output is never silently described as rolled back.

Limits: 500 archive entries, 50 MiB compressed input, 50 MiB total expanded output, 10 MiB per file, and 100:1 for known compression ratios. Unknown compressed sizes remain bounded by the total/per-file expanded limits and operation timeout. Existing destination paths are rejected; extraction never overwrites.

The extractor never creates or follows symlinks. ZIP members are materialized as ordinary SAF files/directories, so archive paths are never interpreted as device filesystem paths. Encrypted archives, unsupported providers, missing grants, malformed ZIPs, and provider failures fail closed.

## Remaining validation

- Instrumented/provider tests for SAF source/destination grants, nested directory creation, conflict handling, low storage, disconnect/revocation, cancellation, and cleanup failure.
- Independent archive readers/openability and malformed ZIP/CRC/ratio/size boundary tests during Stage 10J.
