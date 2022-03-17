/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.internal.tasks

import com.android.build.VariantOutput
import com.android.build.api.artifact.BuildableArtifact
import com.android.build.gradle.internal.ide.FilterDataImpl
import com.android.build.gradle.internal.scope.ApkData
import com.android.build.gradle.internal.scope.BuildElements
import com.android.build.gradle.internal.scope.BuildOutput
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import java.io.File
import java.lang.RuntimeException
import kotlin.test.fail

/**
 * Tests for [PackageForUnitTest]
 */
class PackageForUnitTestTest {

    @Rule
    @JvmField
    val tmpDir: TemporaryFolder = TemporaryFolder()

    lateinit var task: PackageForUnitTest
    lateinit var directoryProperty: BuildableArtifact
    lateinit var outputFolder: File

    @Before
    fun setUp() {
        val project = ProjectBuilder.builder().withProjectDir(
            tmpDir.newFolder()).build()

        task = project.tasks.create("test", PackageForUnitTest::class.java)
        directoryProperty = Mockito.mock(BuildableArtifact::class.java)
        outputFolder= tmpDir.newFolder()
        `when`(directoryProperty.files).thenReturn(setOf(outputFolder))
    }

    @Test(expected = RuntimeException::class)
    fun testNoResources() {
        PackageForUnitTest.apkFrom(directoryProperty)
    }


    @Test
    fun testNoSplits() {
        val buildOutput = BuildOutput(
            InternalArtifactType.PROCESSED_RES,
            ApkData.of(
                VariantOutput.OutputType.MAIN,
                listOf(),
                1
            ),
            File(outputFolder,"the_right_file")
        )
        val buildElements = BuildElements(listOf(buildOutput))
        buildElements.save(outputFolder)

        assertThat(PackageForUnitTest.apkFrom(directoryProperty).name).isEqualTo("the_right_file")
    }

    @Test
    fun testWithABI() {
        val buildOutput = BuildOutput(
            InternalArtifactType.PROCESSED_RES,
            ApkData.of(
                VariantOutput.OutputType.FULL_SPLIT,
                listOf(FilterDataImpl(VariantOutput.FilterType.ABI, "x86")), 1
            ),
            File(outputFolder,"the_right_file")
        )
        val buildElements = BuildElements(listOf(buildOutput))
        buildElements.save(outputFolder)

        assertThat(PackageForUnitTest.apkFrom(directoryProperty).name).isEqualTo("the_right_file")
    }

    @Test
    fun testWithMultipleABIs() {
        val buildElements = BuildElements(listOf(
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(FilterDataImpl(VariantOutput.FilterType.ABI, "x86")), 1
                ),
                File(outputFolder,"the_right_file_1")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(FilterDataImpl(VariantOutput.FilterType.ABI, "arm")), 1
                ),
                File(outputFolder,"the_right_file_2")
            )
        ))
        buildElements.save(outputFolder)

        assertThat(PackageForUnitTest.apkFrom(directoryProperty).name).startsWith("the_right_file")
    }

    @Test
    fun testWithOnlyDensity() {
        val buildElements = BuildElements(listOf(
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(FilterDataImpl(VariantOutput.FilterType.DENSITY, "xhdpi")), 1
                ),
                File(outputFolder,"the_wrong_file")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(FilterDataImpl(VariantOutput.FilterType.DENSITY, "xhdpi")), 1
                ),
                File(outputFolder,"the_wrong_file")
            )))
        buildElements.save(outputFolder)

        try {
            PackageForUnitTest.apkFrom(directoryProperty)
        } catch(e: RuntimeException) {
            assertThat(e.toString()).contains("Cannot find a build output with all resources")
            return
        }
        fail("Expected exception not raised.")
    }

    @Test
    fun testWithCombinedButNoUniversal() {
        val buildElements = BuildElements(listOf(
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(
                        FilterDataImpl(VariantOutput.FilterType.ABI, "x86"),
                        FilterDataImpl(VariantOutput.FilterType.DENSITY, "xxhdpi")),
                    1
                ),
                File(outputFolder,"the_wrong_file_1")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(FilterDataImpl(VariantOutput.FilterType.ABI, "x86")),
                    1
                ),
                File(outputFolder,"the_right_file_1")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(FilterDataImpl(VariantOutput.FilterType.ABI, "arm")),
                    1
                ),
                File(outputFolder,"the_right_file_2")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(
                        FilterDataImpl(VariantOutput.FilterType.ABI, "arm"),
                        FilterDataImpl(VariantOutput.FilterType.DENSITY, "xxhdpi")),
                    1
                ),
                File(outputFolder,"the_wrong_file_2")
            )
        ))
        buildElements.save(outputFolder)

        assertThat(PackageForUnitTest.apkFrom(directoryProperty).name).startsWith("the_right_file")
    }

    @Test
    fun testDensitySplitsWithUniversal() {
        val buildElements = BuildElements(listOf(
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(
                        FilterDataImpl(VariantOutput.FilterType.DENSITY, "hdpi")),
                    1
                ),
                File(outputFolder,"the_wrong_file_1")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(
                        FilterDataImpl(VariantOutput.FilterType.DENSITY, "xhdpi")),
                    1
                ),
                File(outputFolder,"the_wrong_file_2")
            ),
            BuildOutput(
                InternalArtifactType.PROCESSED_RES,
                ApkData.of(
                    VariantOutput.OutputType.FULL_SPLIT,
                    listOf(),
                    1
                ),
                File(outputFolder,"the_right_file")
            )
        ))
        buildElements.save(outputFolder)

        assertThat(PackageForUnitTest.apkFrom(directoryProperty).name).isEqualTo("the_right_file")
    }
}