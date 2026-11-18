# Stage 10H — Local image processing and optional generation providers

**Status:** Local raster transforms and provider boundary implemented in PR #57. No generative provider is registered.  
**Validation:** Transform-payload and remote-consent tests added but not executed. No Gradle tests, build, or device acceptance run.

## Local image processing

The generic `artifact_create` tool advertises `format=image` for local resize/crop/rotate/convert only. The source must be a file inside a persisted Gallery SAF read grant, identified by a current `document_tree` tree URI and document ID. The destination is the existing separately approved SAF output folder.

Supported source and output formats: PNG, JPEG, and WebP. Output MIME is selected from the output filename extension and verified against the SAF provider's returned MIME type.

Optional transforms:
- `resize_width` + `resize_height`: both required, 1–4,096 px
- `crop_x`, `crop_y`, `crop_width`, `crop_height`: all required together; bounds checked against source dimensions
- `rotation_degrees`: 0, 90, 180, or 270
- `quality`: 1–100 (lossy output formats)

Limits:
- 20 MiB compressed source
- 8,192 px per source dimension and 20 MP source area
- 8 MP decode sampling target
- 4,096 px per output dimension and 16 MP output area
- 20 MiB output

The processor uses Android BitmapFactory/Bitmap APIs, bounded source streams, explicit bitmap recycling, and converts allocation failures into tool errors. No image bytes leave the device. EXIF orientation preservation and embedded-image support in PDF/DOCX/PPTX remain follow-up work.

## Optional generative-image provider boundary

Added:
- `ImageGenerationProvider` interface
- `ImageGenerationProviderRegistry`
- `ImageGenerationDataBoundary.ON_DEVICE / REMOTE`
- `GuardedImageGenerationDispatcher`
- `RemoteImageGenerationConsentRequester`

A remote provider must pass through the guarded dispatcher. Before provider invocation, the user-facing consent requester receives provider ID, prompt, output MIME, source-image count, and a visible label for every source image. Missing labels or denied consent prevent invocation. Prompt and source-image count are bounded.

No local or remote generative provider implementation is currently registered, and no generative image capability is exposed to the model. Gemma image-input support is not image generation. A future remote provider must disclose the destination/provider and exact data leaving the device before consent.

## Remaining image work

- Embedded source images in PDF/DOCX/PPTX with explicit SAF grants and decode/dimension bounds.
- EXIF orientation and richer composition/annotation if justified.
- Optional on-device generative provider only if model size, RAM, latency, thermal, and battery gates pass.
- Remote provider integration only with an actual consent UI implementation and explicit opt-in.
