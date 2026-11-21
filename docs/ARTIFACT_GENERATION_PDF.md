# Stage 10C — Native PDF generation and binary output

**Status:** PDF core, SAF/shell binary output, and bounded embedded SAF images are implemented. Shell output is Stage 10I / PR #58; embedded image and EXIF normalization are in the final Stage 10 image integration PR.  
**Validation:** PDF payload and engine tests added but not executed. No Gradle tests, build, or device acceptance run.

## Runtime exposure

The generic `artifact_create` tool now advertises PDF, DOCX, XLSX, PPTX, ZIP, and local image transforms, with SAF and explicit TERMUX/SHIZUKU shell destinations. TERMUX_RISH binary output remains unavailable pending stdin-forwarding proof. Remote image generation is not registered.

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
  - `images`: optional array of up to four SAF image-source objects per page; eight images maximum across the document. Each object contains `source_tree_uri`, `source_document_id`, and optional `alt_text`.

Unknown fields and wrong value types are rejected. Limits include 8,000 characters per text block, 500 characters per table cell, 100 blocks per section, 100,000 total text characters, 100 rendered pages, and 10 MiB output.

The renderer uses Android `PdfDocument`, `StaticLayout`, and platform drawing APIs. It emits actual PDF bytes, page breaks, text wrapping, headings, bullets, simple text tables, embedded raster images, and page numbers. Images are read only from persisted Gallery SAF read grants, normalized for EXIF orientation, sampled and size-bounded before drawing.

## Output and storage safeguards

- One-time approval identifies the exact filename, format, SAF folder or selected shell backend/path, estimated size, and overwrite behavior.
- Existing files are never silently replaced. The current adapter refuses an existing target and does not yet implement atomic replacement.
- The binary SAF adapter independently checks Gallery's persisted read/write tree grant, validates the target folder, creates the requested MIME type, streams directly to the provider output stream, verifies the provider-reported filename/MIME, and deletes partial output on failure/cancellation when cleanup is available.
- Binary SAF output does not reuse the 256 KiB text-file adapter.
- The shared engine counts bytes and computes SHA-256 while streaming, enforces the generator output cap, validates MIME type and file signature, and propagates cancellation.
- Raw artifact bytes are never returned to the model.

## Stage 10J validation

- Verify image embedding, output readability, page breaks, malformed content, low storage, revoked grants, and provider failures.
- Shell output uses the Stage 10I Termux stdin-chunk or Shizuku PFD pipe adapter; neither uses the command-only shell transport for artifact bytes. TERMUX_RISH is rejected rather than silently substituted.
