# Stage 10D — Editable DOCX OOXML generation

**Status:** Editable DOCX writer with bounded embedded SAF images implemented in the final Stage 10 image integration PR; independent office-suite openability validation remains Stage 10J.  
**Validation:** Unit tests added but not executed. No Gradle tests, build, or device acceptance run.

## Model-facing payload

The generic `artifact_create` tool now advertises PDF, DOCX, XLSX, PPTX, ZIP, and local image transforms, with SAF and explicit TERMUX/SHIZUKU shell destinations. TERMUX_RISH binary output remains unavailable pending stdin-forwarding proof.

DOCX payload is a strict JSON object:
- `title`: required string, up to 200 characters
- `header` / `footer`: optional strings, up to 500 characters
- `sections`: required array of 1–100 sections
- `images`: optional array of up to four SAF image-source objects for the document; each contains `source_tree_uri`, `source_document_id`, and optional `alt_text`.
- Each section supports `heading`, `paragraphs`, `bullet_items`, and `table` (rows of 1–8 string cells)

Unknown keys and wrong types are rejected. Per-block, block-count, table-row/cell, XML character, total text, common request-size, and output-byte bounds apply.

## Package structure

The writer emits a genuine editable OOXML ZIP package:
- `[Content_Types].xml`
- `_rels/.rels`
- `word/document.xml`
- `word/styles.xml`
- `word/_rels/document.xml.rels`
- Optional `word/header1.xml` and `word/footer1.xml`
- Optional `word/media/imageN.jpg|png` assets with document relationships and inline DrawingML picture markup

It provides Normal, Title, and Heading 1 styles, editable paragraphs and tables, A4 page layout, and optional header/footer relationships. Bullets are represented as editable bullet-prefixed paragraphs; a full Word numbering definition is not yet included.

All user text is XML-escaped and checked against XML 1.0 character rules. No macros, scripts, external templates, embedded objects, or formulas are executed or evaluated. The package is streamed with JDK `ZipOutputStream`; no office runtime or third-party document dependency is added.

## Stage 10J validation

- Validate image relationships, package integrity, and openability with independent office readers.
- Native Word numbering definitions and richer layout semantics remain optional future enhancements.
