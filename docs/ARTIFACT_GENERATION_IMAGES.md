# Stage 10H — Local image processing and optional generation providers

**Status:** Local raster transforms, bounded SAF image embedding, and a guarded Firebase Gemini remote image provider are implemented. No on-device generative provider is registered.  
**Validation:** Transform-payload and remote-consent tests added but not executed. No Gradle tests, build, or device acceptance run.

## Local image processing

The generic `artifact_create` tool advertises `format=image` for local resize/crop/rotate/convert only. The source must be a file inside a persisted Gallery SAF read grant, identified by a current `document_tree` tree URI and document ID. The destination may be the separately approved SAF output folder or an explicitly selected TERMUX/SHIZUKU shell path through the Stage 10I binary adapters; TERMUX_RISH is not available for binary output.

Supported source and output formats: PNG, JPEG, and WebP. Output MIME is selected from the output filename extension and validated by the generator; SAF additionally verifies the provider-reported MIME type.

Additional local operations include EXIF-aware orientation normalization, composition from bounded SAF sources, and text annotation.

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

Firebase Gemini image generation is registered only when a Firebase app is configured. The dispatcher requires one-time user consent before provider invocation, disclosing the provider ID, exact prompt, output MIME, and visible label for every source image. The provider accepts only SAF content URIs under persisted read grants, normalizes EXIF orientation, caps each source at 8 MiB and all source uploads at 12 MiB, caps output at 4 MiB, and returns image bytes only to the local artifact engine. Denial, timeout, missing labels, invalid grants, or missing provider configuration fail closed. No on-device generative provider is registered; Gemma 4 E2B image input is not image generation.

## Stage 10J validation

- Exercise embedded image sources, EXIF rotations/flips, alpha flattening, decode/output bounds, cancellation, and provider failures.
- Measure Firebase Gemini request latency, upload disclosure behavior, output bounds, RAM, battery, and thermals on the target device.

On-device generative image creation remains intentionally unavailable until a real model/provider passes S21 memory, latency, thermal, storage, and battery gates.
