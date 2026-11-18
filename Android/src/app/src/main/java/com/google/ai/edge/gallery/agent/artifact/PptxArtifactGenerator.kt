/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal editable PowerPoint OOXML writer with a real presentation/slide/master/layout/theme graph. */
class PptxArtifactGenerator(context: Context? = null) : ArtifactGenerator {
  private val imageLoader = context?.let(::ArtifactImageSourceLoader)
  override val format = ArtifactFormat.PPTX
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? =
    (request.payload.toString().length * ESTIMATED_BYTES_PER_CHARACTER)
      .toLong().coerceAtLeast(MIN_ESTIMATED_BYTES).coerceAtMost(maxOutputBytes)

  fun validatePayload(payload: JsonObject) { PptxPayload.parse(payload) }

  override fun write(request: ArtifactCreateRequest, output: OutputStream): String {
    require(request.format == ArtifactFormat.PPTX) { "PPTX generator received a non-PPTX request." }
    val presentation = PptxPayload.parse(request.payload)
    val imageAssetsBySlide = presentation.slides.map { slide ->
      slide.images.map { image ->
        checkNotNull(imageLoader) { "Embedded PPTX images require the Gallery application context." }.load(image)
      }
    }
    val allImages = imageAssetsBySlide.flatten()
    require(allImages.size <= MAX_TOTAL_EMBEDDED_IMAGES) {
      "Presentation exceeds the $MAX_TOTAL_EMBEDDED_IMAGES embedded image limit."
    }
    require(allImages.sumOf { it.bytes.size } <= MAX_TOTAL_EMBEDDED_IMAGE_BYTES) {
      "Embedded PPTX images exceed the $MAX_TOTAL_EMBEDDED_IMAGE_BYTES byte combined limit."
    }
    var mediaIndex = 0
    val indexedImagesBySlide = imageAssetsBySlide.map { slideImages ->
      slideImages.map { image -> PptxEmbeddedImage(++mediaIndex, image) }
    }
    val zip = ZipOutputStream(output).apply { setLevel(COMPRESSION_LEVEL) }
    addEntry(zip, "[Content_Types].xml", contentTypes(presentation.slides.size, allImages))
    addEntry(zip, "_rels/.rels", rootRelationships())
    addEntry(zip, "ppt/presentation.xml", presentationXml(presentation.slides.size))
    addEntry(zip, "ppt/_rels/presentation.xml.rels", presentationRelationships(presentation.slides.size))
    addEntry(zip, "ppt/slideMasters/slideMaster1.xml", slideMasterXml())
    addEntry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRelationships())
    addEntry(zip, "ppt/slideLayouts/slideLayout1.xml", slideLayoutXml())
    addEntry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRelationships())
    addEntry(zip, "ppt/theme/theme1.xml", themeXml())
    presentation.slides.forEachIndexed { index, slide ->
      val images = indexedImagesBySlide[index]
      addEntry(zip, "ppt/slides/slide${index + 1}.xml", slideXml(slide, images.firstOrNull()))
      addEntry(zip, "ppt/slides/_rels/slide${index + 1}.xml.rels", slideRelationships(images.firstOrNull()))
      images.forEach { image ->
        addBinaryEntry(zip, "ppt/media/image${image.mediaIndex}.${image.asset.extension}", image.asset.bytes)
      }
    }
    zip.finish()
    zip.flush()
    return format.mimeType
  }

  private fun addEntry(zip: ZipOutputStream, name: String, value: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(value.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
  }

  private fun addBinaryEntry(zip: ZipOutputStream, name: String, value: ByteArray) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(value)
    zip.closeEntry()
  }

  private fun contentTypes(slideCount: Int, images: List<ArtifactEmbeddedImageAsset>) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
    append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
    append("""<Default Extension="xml" ContentType="application/xml"/>""")
    if (images.any { it.extension == "jpg" }) append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
    if (images.any { it.extension == "png" }) append("""<Default Extension="png" ContentType="image/png"/>""")
    append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
    append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
    append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
    append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
    repeat(slideCount) { index ->
      append("""<Override PartName="/ppt/slides/slide${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
    }
    append("</Types>")
  }

  private fun rootRelationships() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>""" +
      "</Relationships>"

  private fun presentationXml(slideCount: Int) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
    append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>""")
    repeat(slideCount) { index ->
      append("""<p:sldId id="${256 + index}" r:id="rId${index + 2}"/>""")
    }
    append("""</p:sldIdLst><p:sldSz cx="$SLIDE_WIDTH" cy="$SLIDE_HEIGHT" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/>""")
    append("""<p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr></p:defaultTextStyle></p:presentation>""")
  }

  private fun presentationRelationships(slideCount: Int) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
    append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
    repeat(slideCount) { index ->
      append("""<Relationship Id="rId${index + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${index + 1}.xml"/>""")
    }
    append("</Relationships>")
  }

  private fun slideMasterXml() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""" +
      """<p:cSld name="Gallery Master"><p:spTree>""" + groupShapeTree() + """</p:spTree></p:cSld>""" +
      """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
      """<p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"""

  private fun slideMasterRelationships() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
      """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>""" +
      "</Relationships>"

  private fun slideLayoutXml() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1">""" +
      """<p:cSld name="Blank"><p:spTree>""" + groupShapeTree() + """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

  private fun slideLayoutRelationships() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>""" +
      "</Relationships>"

  private fun slideRelationships(image: PptxEmbeddedImage?) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
    append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""")
    if (image != null) {
      append("""<Relationship Id="rIdImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${image.mediaIndex}.${image.asset.extension}"/>""")
    }
    append("</Relationships>")
  }

  private fun themeXml() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Gallery"><a:themeElements>""" +
      """<a:clrScheme name="Gallery"><a:dk1><a:srgbClr val="202124"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="3C4043"/></a:dk2><a:lt2><a:srgbClr val="F8F9FA"/></a:lt2>""" +
      """<a:accent1><a:srgbClr val="1A73E8"/></a:accent1><a:accent2><a:srgbClr val="188038"/></a:accent2><a:accent3><a:srgbClr val="F9AB00"/></a:accent3><a:accent4><a:srgbClr val="D93025"/></a:accent4><a:accent5><a:srgbClr val="A142F4"/></a:accent5><a:accent6><a:srgbClr val="12B5CB"/></a:accent6><a:hlink><a:srgbClr val="1A73E8"/></a:hlink><a:folHlink><a:srgbClr val="681DA8"/></a:folHlink></a:clrScheme>""" +
      """<a:fontScheme name="Gallery"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
      """<a:fmtScheme name="Gallery"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>""" +
      """<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>""" +
      """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
      """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>""" +
      """</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""

  private fun groupShapeTree() =
    """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
      """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

  private fun slideXml(slide: PptxSlide, image: PptxEmbeddedImage?) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree>""")
    append(groupShapeTree())
    append(textShape(2, "Title", TITLE_X, TITLE_Y, TITLE_CX, TITLE_CY, slide.title, TITLE_FONT_SIZE, true))
    val body = buildList {
      slide.subtitle?.let { add(SlideParagraph(it, false, true)) }
      slide.paragraphs.forEach { add(SlideParagraph(it, false, false)) }
      slide.bullets.forEach { add(SlideParagraph(it, true, false)) }
    }
    if (body.isNotEmpty()) {
      append(textShape(3, "Body", BODY_X, BODY_Y, if (image == null) BODY_CX else BODY_CX_WITH_IMAGE, BODY_CY, body, BODY_FONT_SIZE))
    }
    image?.let { append(pictureShape(it)) }
    append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
  }

  private fun pictureShape(image: PptxEmbeddedImage): String {
    val maxWidth = IMAGE_CX
    val maxHeight = IMAGE_CY
    val scale = minOf(
      maxWidth.toDouble() / image.asset.width,
      maxHeight.toDouble() / image.asset.height,
      1.0,
    )
    val width = (image.asset.width * scale).toLong().coerceAtLeast(1L)
    val height = (image.asset.height * scale).toLong().coerceAtLeast(1L)
    val x = IMAGE_X + (maxWidth - width) / 2
    val y = IMAGE_Y + (maxHeight - height) / 2
    val alt = xmlEscape(image.asset.altText)
    return """<p:pic><p:nvPicPr><p:cNvPr id="4" name="Gallery image ${image.mediaIndex}" descr="$alt"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdImage"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
  }

  private fun textShape(
    id: Int,
    name: String,
    x: Long,
    y: Long,
    width: Long,
    height: Long,
    text: String,
    fontSize: Int,
    bold: Boolean,
  ): String = textShape(id, name, x, y, width, height, listOf(SlideParagraph(text, false, bold)), fontSize)

  private fun textShape(
    id: Int,
    name: String,
    x: Long,
    y: Long,
    width: Long,
    height: Long,
    paragraphs: List<SlideParagraph>,
    fontSize: Int,
  ) = buildString {
    append("""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""")
    append("""<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>""")
    append("""<p:txBody><a:bodyPr wrap="square"><a:normAutofit/></a:bodyPr><a:lstStyle/>""")
    paragraphs.forEach { paragraph ->
      append("<a:p>")
      when {
        paragraph.bullet -> append("""<a:pPr marL="342900" indent="-228600"><a:buChar char="•"/></a:pPr>""")
        paragraph.bold -> append("""<a:pPr><a:buNone/></a:pPr>""")
        else -> append("<a:pPr/>")
      }
      append("""<a:r><a:rPr lang="en-US" sz="$fontSize"${if (paragraph.bold) " b=\"1\"" else ""}/><a:t xml:space="preserve">${xmlEscape(paragraph.text)}</a:t></a:r><a:endParaRPr lang="en-US" sz="$fontSize"/></a:p>""")
    }
    append("</p:txBody></p:sp>")
  }

  private fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
      when (char) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(char)
      }
    }
  }

  private data class SlideParagraph(val text: String, val bullet: Boolean, val bold: Boolean)
  private data class PptxEmbeddedImage(val mediaIndex: Int, val asset: ArtifactEmbeddedImageAsset)
  private data class PptxSlide(
    val title: String,
    val subtitle: String?,
    val paragraphs: List<String>,
    val bullets: List<String>,
    val images: List<ArtifactEmbeddedImageRequest>,
  )
  private data class PptxPresentation(val slides: List<PptxSlide>)

  private object PptxPayload {
    fun parse(payload: JsonObject): PptxPresentation {
      val unknown = payload.keySet() - setOf("slides")
      require(unknown.isEmpty()) { "Unsupported PPTX payload field(s): ${unknown.sorted().joinToString()}." }
      val slides = payload.get("slides")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw IllegalArgumentException("PPTX payload requires a slides array.")
      require(slides.size() in 1..MAX_SLIDES) { "PPTX must contain 1–$MAX_SLIDES slides." }
      var totalCharacters = 0
      val parsed = slides.mapIndexed { index, element ->
        require(element.isJsonObject) { "Slide ${index + 1} must be an object." }
        val slide = element.asJsonObject
        val unexpected = slide.keySet() - setOf("title", "subtitle", "paragraphs", "bullet_items", "images")
        require(unexpected.isEmpty()) { "Unsupported slide field(s): ${unexpected.sorted().joinToString()}." }
        val title = string(slide, "title", MAX_TITLE_CHARS)
        require(title.isNotBlank()) { "Slide ${index + 1} requires a non-empty title." }
        val subtitle = optionalString(slide, "subtitle", MAX_SUBTITLE_CHARS)
        val paragraphs = stringArray(slide, "paragraphs")
        val bullets = stringArray(slide, "bullet_items")
        val images = ArtifactEmbeddedImageArguments.parse(slide.get("images"))
        require(images.size <= 1) { "Slide ${index + 1} may contain at most one embedded image." }
        require(paragraphs.size + bullets.size <= MAX_BLOCKS_PER_SLIDE) {
          "Slide ${index + 1} exceeds $MAX_BLOCKS_PER_SLIDE body text blocks."
        }
        val slideCharacters = title.length + (subtitle?.length ?: 0) +
          paragraphs.sumOf { it.length } + bullets.sumOf { it.length }
        require(slideCharacters <= MAX_CHARACTERS_PER_SLIDE) {
          "Slide ${index + 1} exceeds $MAX_CHARACTERS_PER_SLIDE characters."
        }
        totalCharacters += slideCharacters
        require(totalCharacters <= MAX_TOTAL_CHARACTERS) {
          "Presentation text exceeds $MAX_TOTAL_CHARACTERS characters."
        }
        PptxSlide(title, subtitle, paragraphs, bullets, images)
      }
      return PptxPresentation(parsed)
    }

    private fun string(obj: JsonObject, key: String, max: Int): String {
      val value = obj.get(key)
      require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
      return value.asString.also {
        require(it.length <= max) { "$key exceeds $max characters." }
        require(isXml10Text(it)) { "$key contains characters not permitted in XML 1.0." }
      }
    }

    private fun optionalString(obj: JsonObject, key: String, max: Int): String? {
      if (!obj.has(key)) return null
      return string(obj, key, max)
    }

    private fun stringArray(obj: JsonObject, key: String): List<String> {
      if (!obj.has(key)) return emptyList()
      val array = obj.get(key)
      require(array.isJsonArray) { "$key must be an array of strings." }
      require(array.asJsonArray.size() <= MAX_BLOCKS_PER_SLIDE) { "$key has too many items." }
      return array.asJsonArray.mapIndexed { index, item ->
        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "$key item ${index + 1} must be a string." }
        item.asString.also {
          require(it.length <= MAX_BLOCK_CHARS) { "$key item exceeds $MAX_BLOCK_CHARS characters." }
          require(isXml10Text(it)) { "$key item contains characters not permitted in XML 1.0." }
        }
      }
    }

    private fun isXml10Text(value: String): Boolean {
      var index = 0
      while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val valid = codePoint == 0x9 || codePoint == 0xa || codePoint == 0xd ||
          codePoint in 0x20..0xd7ff || codePoint in 0xe000..0xfffd ||
          codePoint in 0x10000..0x10ffff
        if (!valid) return false
        index += Character.charCount(codePoint)
      }
      return true
    }
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 30L * 1024L * 1024L
    const val ESTIMATED_BYTES_PER_CHARACTER = 16
    const val MIN_ESTIMATED_BYTES = 4_096L
    const val COMPRESSION_LEVEL = 6
    const val MAX_SLIDES = 50
    const val MAX_TITLE_CHARS = 200
    const val MAX_SUBTITLE_CHARS = 500
    const val MAX_BLOCK_CHARS = 3_000
    const val MAX_BLOCKS_PER_SLIDE = 50
    const val MAX_CHARACTERS_PER_SLIDE = 3_000
    const val MAX_TOTAL_CHARACTERS = 100_000
    const val MAX_TOTAL_EMBEDDED_IMAGES = 4
    const val MAX_TOTAL_EMBEDDED_IMAGE_BYTES = 8 * 1024 * 1024
    const val SLIDE_WIDTH = 12_192_000L
    const val SLIDE_HEIGHT = 6_858_000L
    const val EMU = 914_400L
    const val TITLE_X = EMU / 2
    const val TITLE_Y = EMU / 3
    const val TITLE_CX = SLIDE_WIDTH - EMU
    const val TITLE_CY = EMU
    const val BODY_X = EMU * 3 / 4
    const val BODY_Y = EMU * 17 / 10
    const val BODY_CX = SLIDE_WIDTH - EMU * 3 / 2
    const val BODY_CX_WITH_IMAGE = 5_300_000L
    const val BODY_CY = SLIDE_HEIGHT - EMU * 23 / 10
    const val IMAGE_X = 6_650_000L
    const val IMAGE_Y = 1_650_000L
    const val IMAGE_CX = 5_000_000L
    const val IMAGE_CY = 4_800_000L
    const val TITLE_FONT_SIZE = 3000
    const val BODY_FONT_SIZE = 1800
  }
}
