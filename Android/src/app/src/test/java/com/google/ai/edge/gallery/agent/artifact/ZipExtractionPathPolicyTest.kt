package com.google.ai.edge.gallery.agent.artifact

import org.junit.Assert.assertEquals
import org.junit.Test

class ZipExtractionPathPolicyTest {
  @Test fun acceptsSafeRelativeFileAndDirectoryPaths() {
    assertEquals("docs/readme.md", ZipExtractionPathPolicy.validate("docs/readme.md", false))
    assertEquals("docs", ZipExtractionPathPolicy.validate("docs/", true))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsAbsolutePaths() {
    ZipExtractionPathPolicy.validate("/storage/emulated/0/file.txt", false)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsDotSegmentTraversal() {
    ZipExtractionPathPolicy.validate("docs/../secret.txt", false)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsBackslashAndDrivePrefixes() {
    ZipExtractionPathPolicy.validate("C:\\temp\\secret.txt", false)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsReservedWindowsDeviceNames() {
    ZipExtractionPathPolicy.validate("docs/CON.txt", false)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsEmptyPathSegments() {
    ZipExtractionPathPolicy.validate("docs//secret.txt", false)
  }
}
