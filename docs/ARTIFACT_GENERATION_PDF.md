# Stage 10C — Native PDF generation and binary SAF output

**Status:** PDF core implemented in PR #51. Optional embedded images and shell binary output remain later work.  
**Validation:** PDF payload and engine tests added but not executed. No Gradle tests, build, or device acceptance run.

## Runtime exposure

The generic `artifact_create` tool is registered in the native Agent Chat runtime, but its advertised enums are derived from the currently implemented adapters: **PDF** and **SAF** only. DOCX, XLSX, PPTX, ZIP, image processing, shell destinations, and remote image generation are not advertised yet.

The model must obtain a persisted Gallery document-tree grant through the existing `document_tree` grants/request_access flow. It may use only a returned tree URI and document ID. No arbitrary path or invented URI is accepted.

## PDF payload

The PDF payload is a strict JSON object:

- `title`: required, 1–200 characters
- `pages`: required array of 1–100 content sections
- Each section may contain:
  - `heading`: optional string
  - `paragraphs`: optional array of strings
  - `bullet_items`: optional array of strings
  - `table`: optional array of rows, each with 1–12 string cells

Unknown fields and wrong value types are rejected. Limits include 8,000 characters per text block, 500 characters per table cell, 100 blocks per section, 100,000 total text characters, 100 rendered pages, and 10 MiB output.

The renderer uses Android `PdfDocument`, `StaticLayout`, and platform drawing APIs. It emits actual PDF bytes, page breaks, text wrapping, headings, bullets, simple text tables, and page numbers. It does not create HTML/text files with a PDF extension.

## Output and storage safeguards

- One-time approval identifies the exact filename, format, SAF folder, estimated size, and overwrite behavior.
- Existing files are never silently replaced. The current adapter refuses an existing target and does not yet implement atomic replacement.
- The binary SAF adapter independently checks Gallery's persisted read/write tree grant, validates the target folder, creates the requested MIME type, streams directly to the provider output stream, verifies the provider-reported filename/MIME, and deletes partial output on failure/cancellation when cleanup is available.
- Binary SAF output does not reuse the 256 KiB text-file adapter.
- The shared engine counts bytes and computes SHA-256 while streaming, enforces the generator output cap, validates MIME type and file signature, and propagates cancellation.
- Raw artifact bytes are never returned to the model.

## Remaining 10C follow-up

- Add bounded optional embedded images using explicit user-granted document sources, decode bounds, and image-dimension limits.
- Add output readability/package checks in the final validation stage.
- Shell binary destinations remain Stage 10I; the command-only shell router is not treated as a binary streaming transport.
