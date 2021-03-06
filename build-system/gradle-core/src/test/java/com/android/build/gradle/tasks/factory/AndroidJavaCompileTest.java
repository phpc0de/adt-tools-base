/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.build.gradle.tasks.factory;

import static com.google.common.truth.Truth.assertThat;

import com.android.build.gradle.tasks.AndroidJavaCompile;
import com.android.build.gradle.tasks.JavaCompileUtils;
import com.android.builder.profile.ProcessProfileWriter;
import com.android.builder.profile.ProcessProfileWriterFactory;
import com.google.wireless.android.sdk.stats.AnnotationProcessorInfo;
import com.google.wireless.android.sdk.stats.GradleBuildVariant;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Test for AndroidJavaCompileTest. */
public class AndroidJavaCompileTest {
    private static final String VARIANT_NAME = "variant";

    @ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();

    Project project;

    @Before
    public void setUp() throws IOException {
        File testDir = temporaryFolder.newFolder();
        project = ProjectBuilder.builder().withProjectDir(testDir).build();
        ProcessProfileWriterFactory.initializeForTests();
    }

    @Test
    public void processAnalyticsEmptyList() throws IOException {
        AndroidJavaCompile task = project.getTasks().create("test", AndroidJavaCompile.class);

        File inputFile = temporaryFolder.newFile();
        Files.write(inputFile.toPath(), "[]".getBytes("utf-8"));
        task.variantName = VARIANT_NAME;

        task.processorListFile =
                project.getLayout().getBuildDirectory().file(inputFile.getAbsolutePath());

        Map<String, Boolean> annotationProcessors =
                JavaCompileUtils.readAnnotationProcessorsFromJsonFile(inputFile);
        JavaCompileUtils.recordAnnotationProcessorsForAnalytics(
                annotationProcessors, project.getPath(), task.variantName);

        Map<String, Boolean> processors = getProcessors();
        assertThat(processors).isEmpty();
    }

    @Test
    public void processAnalyticsMultipleProcessors() throws IOException {
        AndroidJavaCompile task = project.getTasks().create("test", AndroidJavaCompile.class);

        File inputFile = temporaryFolder.newFile();
        Files.write(
                inputFile.toPath(), "{\"processor1\":false,\"processor2\":true}".getBytes("utf-8"));
        task.variantName = VARIANT_NAME;
        task.processorListFile =
                project.getLayout().getBuildDirectory().file(inputFile.getAbsolutePath());

        Map<String, Boolean> annotationProcessors =
                JavaCompileUtils.readAnnotationProcessorsFromJsonFile(inputFile);
        JavaCompileUtils.recordAnnotationProcessorsForAnalytics(
                annotationProcessors, project.getPath(), task.variantName);

        Map<String, Boolean> processors = getProcessors();
        assertThat(processors).containsEntry("processor1", false);
        assertThat(processors).containsEntry("processor2", true);
        assertThat(annotationProcessingIncremental()).isFalse();

        Files.write(
                inputFile.toPath(), "{\"processor1\":true,\"processor2\":true}".getBytes("utf-8"));
        annotationProcessors = JavaCompileUtils.readAnnotationProcessorsFromJsonFile(inputFile);
        JavaCompileUtils.recordAnnotationProcessorsForAnalytics(
                annotationProcessors, project.getPath(), task.variantName);
        assertThat(annotationProcessingIncremental()).isTrue();
    }

    private Map<String, Boolean> getProcessors() {
        GradleBuildVariant.Builder variant =
                ProcessProfileWriter.getOrCreateVariant(project.getPath(), VARIANT_NAME);

        assertThat(variant).isNotNull();
        List<AnnotationProcessorInfo> procs = variant.getAnnotationProcessorsList();
        assertThat(procs).isNotNull();

        return procs.stream()
                .collect(
                        Collectors.toMap(
                                AnnotationProcessorInfo::getSpec,
                                AnnotationProcessorInfo::getIsIncremental));
    }

    private Boolean annotationProcessingIncremental() {
        GradleBuildVariant.Builder variant =
                ProcessProfileWriter.getOrCreateVariant(project.getPath(), VARIANT_NAME);

        assertThat(variant).isNotNull();
        return variant.getIsAnnotationProcessingIncremental();
    }
}
