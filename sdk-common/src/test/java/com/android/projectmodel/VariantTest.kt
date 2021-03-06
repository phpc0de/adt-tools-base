/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.projectmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VariantTest {
    val variant = Variant(name = "name", displayName = "displayName")
    val defaultVariant = Variant(submodulePathOf("foo", "bar", "baz"))

    @Test
    fun testCustomVariant() {
        assertThat(variant.name).isEqualTo("name")
        assertThat(variant.displayName).isEqualTo("displayName")
    }

    @Test
    fun testDefaultVariant() {
        assertThat(defaultVariant.name).isEqualTo("fooBarBaz")
        assertThat(defaultVariant.displayName).isEqualTo("fooBarBaz")
    }
}
