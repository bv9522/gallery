# Stage 10G — Bounded ZIP archive creation

**Status:** ZIP creation core implemented in PR #55. ZIP extraction is not yet implemented.  
**Validation:** Archive/path safety tests added but not executed. No Gradle tests, build, or device acceptance run.

## Model-facing payload

The generic `artifact_create` tool now advertises PDF, DOCX, XLSX, PPTX, and ZIP. SAF remains the only enabled destination.

ZIP creation accepts a strict JSON object with an `entries` array. Each entry contains:
- `path`: a relative archive path
- `content`: UTF-8 text content

Current limits:
- 1–100 entries
- 1 MiB per entry
- 5 MiB total uncompressed content
- 240 characters per path and 128 per path segment
- 20 MiB compressed output cap

## Path and archive safeguards

- Reject absolute paths, drive prefixes, backslashes, NUL/control characters, empty segments, dot segments, traversal segments, overlong segments, and reserved Windows device names.
- Reject duplicate paths case-insensitively.
- Reject file/directory prefix collisions (for example, a file named `docs` alongside `docs/readme.txt`).
- Generate only regular file entries; no symlinks or executable behavior are created.
- Use JDK `ZipOutputStream` with bounded structured UTF-8 content. The existing output engine checks ZIP MIME and PK signature while streaming.
- The current creation adapter creates archives only; it does not advertise or claim extraction.

## Remaining ZIP work

Extraction needs a separate multi-file SAF operation with preflight inspection, exact one-time approval, bounded entry/expanded-byte/compression-ratio/time limits, path validation, duplicate detection, safe directory creation, cancellation, and cleanup of partial output trees. The extractor must never create symlinks; ZIP entries are materialized only as regular files. Extraction is not considered complete until the provider can verify cleanup outcomes.
