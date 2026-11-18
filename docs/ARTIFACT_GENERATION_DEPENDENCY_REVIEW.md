# Stage 10A — Artifact generation dependency and platform preflight

**Date:** 2026-11-18  
**Repository baseline:** Stage 10 architecture PR #48, stacked after Wave 6E PR #47  
**Status:** Dependency/API selection complete. Stage 10I code now implements Termux bounded RUN_COMMAND stdin chunks and Shizuku PFD pipe transfer; TERMUX_RISH remains gated on end-to-end stdin proof. Tests/build/device acceptance remain deferred to Stage 10J.  
**Validation:** Documentation/source inspection only. No Gradle tests, builds, instrumentation tests, or device checks were run.

## Build and platform baseline

The Shizuku app flavor currently declares:

- `minSdk = 31`
- `targetSdk = 37`
- `compileSdk = 37 (minor API 0)`
- Java source/target compatibility 11
- Kotlin JVM target 11
- Existing version catalog includes AndroidX PDF `1.0.0-beta01`, ExifInterface `1.4.1`, Coil `2.7.0`, and Media3 Transformer `1.11.1`.

This baseline is sufficient for the selected platform APIs below. No build was run to measure the resolved dependency graph or APK size; the decision is to add no third-party dependency in Stage 10A, so there is no planned external-library APK increase.

## Format and image implementation decisions

| Capability | Existing/platform option | Stage 10A decision | License and footprint |
|---|---|---|---|
| PDF creation | Android `android.graphics.pdf.PdfDocument` (API 19+) | Use native PdfDocument; stream its completed document to the destination. Bound pages, text, images, and page dimensions. | Android platform API; no added dependency. |
| ZIP create/extract | `java.util.zip.ZipOutputStream`, `ZipInputStream`, CRC/stream primitives | Use JDK streaming ZIP APIs. Add explicit path, entry-count, expanded-size, compression-ratio, duplicate-name, and cancellation defenses in our code. | Java platform API; no added dependency. |
| DOCX | Office Open XML / ECMA-376 package | Do not add Apache POI/docx4j or an office suite. Build a minimal editable OOXML package writer from bounded typed content and ZIP/XML primitives. | No new third-party license or binary. Custom implementation carries package-conformance responsibility; supported OOXML subset must be explicit. |
| XLSX | Office Open XML spreadsheet package | Same minimal OOXML strategy. Use typed cells and explicit string/numeric/boolean/date handling. Do not evaluate formulas; formula support must be a separate allowlisted, inert serialization feature with no external references/macros. | No new third-party license or binary. |
| PPTX | Office Open XML presentation package | Same minimal OOXML strategy with a deliberately small editable subset: slides, text, basic shapes, images, layouts, and relationships. | No new third-party license or binary. |
| Local image processing | Android BitmapFactory/Bitmap/Canvas/Matrix and ImageDecoder (API 28+); existing AndroidX ExifInterface `1.4.1` | Use native decode/transform/encode paths; normalize EXIF orientation. Start with bounded static JPEG/PNG/WebP raster workflows; do not promise animated GIF/video processing in this slice. | Platform APIs plus an existing dependency; no new dependency. |
| Generative images | No image-generation provider is established by the current app dependency catalog. Gemma 4 E2B image input is not image generation. | Define an optional provider interface only. No cloud endpoint, API key, data transfer, or mandatory on-device image model is introduced by the baseline engine. Add a provider only in a separately reviewed opt-in slice. | No dependency or network behavior added. |

### OOXML scope and engineering trade-off

A full general-purpose Office library would add dependency weight and Android compatibility risk. The initial engine will therefore write a narrow, explicit OOXML subset itself using ZIP entries and deterministic XML serialization/escaping. It must emit real package parts, content types, and relationships; file extensions alone are not accepted as proof. Each format adapter owns package construction and validation. Unsupported features are rejected rather than silently flattened or mislabeled. The project will not execute macros, templates, scripts, or workbook formulas.

The OOXML format is standardized by ECMA-376: https://ecma-international.org/publications-and-standards/standards/ecma-376/

## Destination and binary-stream findings

### SAF

The existing `AndroidDocumentTreeAdapter` already uses persisted Gallery-owned tree grants, `DocumentsContract`, and `ContentResolver.openOutputStream`. Its current text-file path converts text to UTF-8 bytes and enforces the 256 KiB text limit. Artifact generation must add a separate binary streaming operation that creates an allowlisted artifact MIME type and writes directly through a document-provider output stream. It must not reuse the text helper or inherit its cap. Failed/cancelled writes must attempt deletion and report cleanup uncertainty.

Relevant Android API: https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)

### Unified shell router

The current `ShellExecutionRequest` accepts command text only (maximum 8,192 characters at the router contract); `ShellExecutionTransport` has no input-stream parameter. The shell route cannot safely accept a complete arbitrary binary artifact embedded in a command string.

- **TERMUX:** The official RUN_COMMAND contract exposes `EXTRA_STDIN` for command stdin on Termux >= 0.109 and documents an approximate 500 KB combined Intent-extra ceiling. The user's installed Termux beta is newer than that baseline, but Gallery's current `TermuxRunCommandClient` does not yet set the stdin extra. Stage 10I should add a bounded, chunked transfer path with generous headroom below the Intent ceiling, explicit backend selection, destination-side hashing/length verification, and atomic finalization. Do not send unbounded artifact bytes in one Intent.
- **SHIZUKU:** The current `IShizukuShellService` AIDL contract accepts command strings and results only. `ShizukuShellUserService` explicitly notes that interactive command stdin is not supported. Add a dedicated file-transfer method using a `ParcelFileDescriptor` pipe (or equivalent bounded streaming primitive), not a large Binder byte array; the service must write under the Shizuku shell identity and enforce path/size/timeout limits.
- **TERMUX_RISH:** This route currently wraps a command in `rish -c` through Termux RUN_COMMAND. Do not assume Termux stdin is forwarded through rish into the Shizuku-side command. Stage 10I deliberately reports this destination unavailable until end-to-end stdin/pipe semantics and cancellation are proven. It never falls back to TERMUX or SHIZUKU.

Official Termux RUN_COMMAND contract: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent  
Android ParcelFileDescriptor reference: https://developer.android.com/reference/android/os/ParcelFileDescriptor

SAF and shell remain separate destination identities. A Gallery SAF grant never becomes a Termux/Shizuku grant, and shell path visibility is never inferred from SAF access.

## License, APK, and resource decision

- No third-party generation library is selected or added in Stage 10A.
- Android platform APIs and JDK ZIP APIs add no separate packaged library license or dependency payload.
- Existing ExifInterface is reused; no second image metadata stack is introduced.
- A custom minimal OOXML writer avoids Apache POI/docx4j/office-suite dependency weight, but shifts conformance and compatibility responsibility into our format adapters. The implementation must be intentionally narrow and backed by package-structure tests added now and executed only at the final validation stage.
- APK size, dex count, memory, CPU, latency, storage, and thermal impact remain unmeasured until the owner-authorized consolidated build/device phase. Do not describe the dependency review as a measured performance result.

## Stage 10A exit and next slice

**10A is complete** for platform/API/dependency strategy. Stage 10B should define the shared `artifact.create` contract, typed payloads/results, per-format limits, approval scope, cancellation, temporary-output lifecycle, and destination interfaces. Stage 10I must implement and verify each binary transfer path independently. Stage 10 tests are added with each slice but remain unexecuted until Stage 10A–10J onboarding is complete.
