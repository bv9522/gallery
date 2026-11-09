/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuManagerPackageResolverTest {
  @Test
  fun selectsRenamedManagerPackageWithoutStockPackageAssumptions() {
    assertEquals(
      "moe.shizuku.privileged.api.ropmz",
      ShizukuManagerPackageResolver.selectManagerPackage(
        listOf("moe.shizuku.privileged.api.ropmz")
      ),
    )
  }

  @Test
  fun duplicateCandidatesAreCollapsed() {
    assertEquals(
      "org.example.shizuku",
      ShizukuManagerPackageResolver.selectManagerPackage(
        listOf("org.example.shizuku", "org.example.shizuku")
      ),
    )
  }

  @Test
  fun missingManagerProducesActionableError() {
    val error = runCatching {
      ShizukuManagerPackageResolver.selectManagerPackage(emptyList())
    }.exceptionOrNull()

    assertTrue(error is IllegalStateException)
    assertTrue(error?.message.orEmpty().contains("No installed Shizuku manager"))
  }

  @Test
  fun ambiguousManagersFailClosedInsteadOfGuessing() {
    val error = runCatching {
      ShizukuManagerPackageResolver.selectManagerPackage(
        listOf("org.example.first", "org.example.second")
      )
    }.exceptionOrNull()

    assertTrue(error is IllegalStateException)
    assertTrue(error?.message.orEmpty().contains("Multiple Shizuku manager packages"))
  }
}
