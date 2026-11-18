# Stage 10F — Editable PPTX OOXML generation

**Status:** Editable PPTX package writer with one bounded embedded SAF image per slide (four per presentation) implemented; richer shapes/layouts and independent office-reader validation remain future work.  
**Validation:** Package and XML well-formedness tests added but not executed. No Gradle tests, build, or device acceptance run.

## Model-facing payload

The generic `artifact_create` tool now advertises PDF, DOCX, XLSX, PPTX, ZIP, and local image transforms, with SAF and explicit TERMUX/SHIZUKU shell destinations. TERMUX_RISH binary output remains unavailable pending stdin-forwarding proof.

PPTX payload is a strict JSON object containing `slides`:
- 1–50 slides
- Each slide requires a title (up to 200 characters)
- Optional subtitle (up to 500 characters)
- Optional `paragraphs` and `bullet_items` arrays
- Optional `images`: at most one SAF image-source object per slide; four images maximum per presentation
- Up to 50 body text blocks and 3,000 total characters per slide
- 100,000 total presentation characters

All strings are validated against XML 1.0 character rules and escaped before insertion. Unknown keys and wrong types are rejected.

## OOXML package

The writer emits:
- `[Content_Types].xml` and root relationships
- `ppt/presentation.xml` and presentation relationships
- A slide master, blank slide layout, and theme
- `ppt/slides/slideN.xml` with per-slide layout and optional image relationships
- `ppt/media/imageN.jpg|png` normalized raster assets

The initial design is 16:9 widescreen with editable title/body text boxes, editable bullet paragraphs, automatic text fitting, and a basic Gallery theme. JDK `ZipOutputStream` streams the package; no office runtime or third-party dependency is added.

## Stage 10J validation

- Validate embedded image relationships and openability with PowerPoint/LibreOffice.
- Additional editable shapes/layout variants and more polished theme defaults remain optional future enhancements.
