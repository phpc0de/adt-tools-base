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

package com.android.build.gradle.internal.tasks;

import static com.android.build.gradle.internal.scope.InternalArtifactType.APK_FOR_LOCAL_TEST;
import static com.android.build.gradle.internal.scope.InternalArtifactType.MERGED_ASSETS;
import static com.android.build.gradle.internal.scope.InternalArtifactType.PROCESSED_RES;

import com.android.annotations.NonNull;
import com.android.build.VariantOutput;
import com.android.build.api.artifact.BuildableArtifact;
import com.android.build.gradle.internal.scope.BuildArtifactsHolder;
import com.android.build.gradle.internal.scope.BuildElements;
import com.android.build.gradle.internal.scope.BuildOutput;
import com.android.build.gradle.internal.scope.ExistingBuildElements;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction;
import com.android.utils.FileUtils;
import com.android.utils.PathUtils;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

@CacheableTask
public class PackageForUnitTest extends NonIncrementalTask {
    BuildableArtifact resApk;
    ListProperty<Directory> mergedAssets;
    File apkForUnitTest;

    @Override
    protected void doTaskAction() throws IOException {
        // this can certainly be optimized by making it incremental...

        FileUtils.copyFile(apkFrom(resApk), apkForUnitTest);

        URI uri = URI.create("jar:" + apkForUnitTest.toURI());
        try (FileSystem apkFs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            Path apkAssetsPath = apkFs.getPath("/assets");
            for (Directory mergedAsset : mergedAssets.get()) {
                final Path mergedAssetsPath = mergedAsset.getAsFile().toPath();
                Files.walkFileTree(mergedAssetsPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path path,
                            BasicFileAttributes basicFileAttributes)
                            throws IOException {
                        String relativePath = PathUtils.toSystemIndependentPath(
                                mergedAssetsPath.relativize(path));
                        Path destPath = apkAssetsPath.resolve(relativePath);
                        Files.createDirectories(destPath.getParent());
                        Files.copy(path, destPath);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public BuildableArtifact getResApk() {
        return resApk;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public ListProperty<Directory> getMergedAssets() {
        return mergedAssets;
    }

    @OutputFile
    public File getApkForUnitTest() {
        return apkForUnitTest;
    }

    @NonNull
    static File apkFrom(BuildableArtifact compiledResourcesZip) {
        BuildElements builtElements =
                ExistingBuildElements.from(PROCESSED_RES, compiledResourcesZip);

        if (builtElements.size() == 1) {
            return Iterables.getOnlyElement(builtElements).getOutputFile();
        }
        for (BuildOutput buildOutput : builtElements.getElements()) {
            if (buildOutput.getFilters().isEmpty()) {
                // universal APK, take it !
                return buildOutput.getOutputFile();
            }
            if (buildOutput.getFilters().size() == 1
                    && buildOutput.getFilter(VariantOutput.FilterType.ABI.name()) != null) {

                // the only filter is ABI, good enough for getting all resources.
                return buildOutput.getOutputFile();
            }
        }

        // if we are here, we could not find an appropriate build output, raise this as an error.
        if (builtElements.isEmpty()) {
            throw new RuntimeException("No resources build output, please file a bug.");
        }
        StringBuilder sb = new StringBuilder("Found following build outputs : \n");
        builtElements.forEach(
                it -> {
                    sb.append("BuildOutput: ${Joiner.on(',').join(it.filters)}\n");
                });
        sb.append("Cannot find a build output with all resources, please file a bug.");
        throw new RuntimeException(sb.toString());
    }

    public static class CreationAction extends VariantTaskCreationAction<PackageForUnitTest> {
        private File apkForUnitTest;

        public CreationAction(@NonNull VariantScope scope) {
            super(scope);
        }

        @NonNull
        @Override
        public String getName() {
            return getVariantScope().getTaskName("package", "ForUnitTest");
        }

        @NonNull
        @Override
        public Class<PackageForUnitTest> getType() {
            return PackageForUnitTest.class;
        }

        @Override
        public void preConfigure(@NonNull String taskName) {
            super.preConfigure(taskName);
            apkForUnitTest =
                    getVariantScope()
                            .getArtifacts()
                            .appendArtifact(APK_FOR_LOCAL_TEST, taskName, "apk-for-local-test.ap_");
        }

        @Override
        public void configure(@NonNull PackageForUnitTest task) {
            super.configure(task);

            BuildArtifactsHolder artifacts = getVariantScope().getArtifacts();
            task.resApk = artifacts.getArtifactFiles(PROCESSED_RES);
            task.mergedAssets = artifacts.getFinalProducts(MERGED_ASSETS);
            task.apkForUnitTest = apkForUnitTest;
        }
    }
}
