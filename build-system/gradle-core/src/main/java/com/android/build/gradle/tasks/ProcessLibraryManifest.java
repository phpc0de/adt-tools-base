/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.build.gradle.tasks;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.core.VariantConfiguration;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.CoreProductFlavor;
import com.android.build.gradle.internal.scope.ApkData;
import com.android.build.gradle.internal.scope.BuildArtifactsHolder;
import com.android.build.gradle.internal.scope.BuildOutput;
import com.android.build.gradle.internal.scope.InternalArtifactType;
import com.android.build.gradle.internal.scope.OutputScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.TaskInputHelper;
import com.android.build.gradle.internal.tasks.Workers;
import com.android.build.gradle.internal.tasks.manifest.ManifestHelperKt;
import com.android.builder.model.ApiVersion;
import com.android.builder.model.ProductFlavor;
import com.android.ide.common.workers.WorkerExecutorFacade;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.XmlDocument;
import com.android.utils.FileUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.apache.tools.ant.BuildException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.workers.WorkerExecutor;

/** a Task that only merge a single manifest with its overlays. */
@CacheableTask
public abstract class ProcessLibraryManifest extends ManifestProcessorTask {

    private Supplier<String> minSdkVersion;
    private Supplier<String> targetSdkVersion;
    private Supplier<Integer> maxSdkVersion;

    private VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor>
            variantConfiguration;
    private OutputScope outputScope;

    private final RegularFileProperty manifestOutputFile;
    private final WorkerExecutor workerExecutor;

    private boolean isNamespaced;

    @Inject
    public ProcessLibraryManifest(ObjectFactory objectFactory, WorkerExecutor workerExecutor) {
        super(objectFactory);
        manifestOutputFile = objectFactory.fileProperty();
        this.workerExecutor = workerExecutor;
    }

    @OutputFile
    @NonNull
    public RegularFileProperty getManifestOutputFile() {
        return manifestOutputFile;
    }

    @Override
    protected void doFullTaskAction() {
        try (WorkerExecutorFacade workers =
                Workers.INSTANCE.preferWorkers(getProject().getName(), getPath(), workerExecutor)) {
            DirectoryProperty manifestOutputDirectory = getManifestOutputDirectory();
            DirectoryProperty aaptFriendlyManifestOutputDirectory =
                    getAaptFriendlyManifestOutputDirectory();

            workers.submit(
                    ProcessLibRunnable.class,
                    new ProcessLibParams(
                            getAaptFriendlyManifestOutputFile(),
                            isNamespaced,
                            getMainManifest(),
                            getManifestOverlays(),
                            Collections.emptyList(),
                            getPackageOverride(),
                            getVersionCode(),
                            getVersionName(),
                            getMinSdkVersion(),
                            getTargetSdkVersion(),
                            getMaxSdkVersion(),
                            manifestOutputFile.get().getAsFile(),
                            variantConfiguration.getManifestPlaceholders(),
                            getReportFile(),
                            getMergeBlameFile().get().getAsFile(),
                            manifestOutputDirectory.isPresent()
                                    ? manifestOutputDirectory.get().getAsFile()
                                    : null,
                            aaptFriendlyManifestOutputDirectory.isPresent()
                                    ? aaptFriendlyManifestOutputDirectory.get().getAsFile()
                                    : null,
                            outputScope.getMainSplit()));
        }
    }

    private static class ProcessLibParams implements Serializable {
        @Nullable private final File aaptFriendlyManifestOutputFile;
        private final boolean isNamespaced;
        @NonNull private final File mainManifest;
        @NonNull private final List<File> manifestOverlays;
        @NonNull private final List<File> navigationFiles;
        @NonNull private final String packageOverride;
        private final int versionCode;
        @Nullable private final String versionName;
        @Nullable private final String minSdkVersion;
        @Nullable private final String targetSdkVersion;
        @Nullable private final Integer maxSdkVersion;
        @NonNull private final File manifestOutputFile;
        @NonNull private final Map<String, Object> manifestPlaceholders;
        @NonNull private final File reportFile;
        @NonNull private final File mergeBlameFile;
        @Nullable private final File manifestOutputDirectory;
        @Nullable private final File aaptFriendlyManifestOutputDirectory;
        @NonNull private final ApkData mainSplit;

        private ProcessLibParams(
                @Nullable File aaptFriendlyManifestOutputFile,
                boolean isNamespaced,
                @NonNull File mainManifest,
                @NonNull List<File> manifestOverlays,
                @NonNull List<File> navigationFiles,
                @NonNull String packageOverride,
                int versionCode,
                @Nullable String versionName,
                @Nullable String minSdkVersion,
                @Nullable String targetSdkVersion,
                @Nullable Integer maxSdkVersion,
                @NonNull File manifestOutputFile,
                @NonNull Map<String, Object> manifestPlaceholders,
                @NonNull File reportFile,
                @NonNull File mergeBlameFile,
                @Nullable File manifestOutputDirectory,
                @Nullable File aaptFriendlyManifestOutputDirectory,
                @NonNull ApkData mainSplit) {
            this.aaptFriendlyManifestOutputFile = aaptFriendlyManifestOutputFile;
            this.isNamespaced = isNamespaced;
            this.mainManifest = mainManifest;
            this.manifestOverlays = manifestOverlays;
            this.navigationFiles = navigationFiles;
            this.packageOverride = packageOverride;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.minSdkVersion = minSdkVersion;
            this.targetSdkVersion = targetSdkVersion;
            this.maxSdkVersion = maxSdkVersion;
            this.manifestOutputFile = manifestOutputFile;
            this.manifestPlaceholders = manifestPlaceholders;
            this.reportFile = reportFile;
            this.mergeBlameFile = mergeBlameFile;
            this.manifestOutputDirectory = manifestOutputDirectory;
            this.aaptFriendlyManifestOutputDirectory = aaptFriendlyManifestOutputDirectory;
            this.mainSplit = mainSplit;
        }
    }

    public static class ProcessLibRunnable implements Runnable {
        @NonNull private final ProcessLibParams params;

        @Inject
        public ProcessLibRunnable(@NonNull ProcessLibParams params) {
            this.params = params;
        }

        @Override
        public void run() {
            Collection<ManifestMerger2.Invoker.Feature> optionalFeatures =
                    params.isNamespaced
                            ? Collections.singletonList(
                                    ManifestMerger2.Invoker.Feature.FULLY_NAMESPACE_LOCAL_RESOURCES)
                            : Collections.emptyList();
            MergingReport mergingReport =
                    ManifestHelperKt.mergeManifestsForApplication(
                            params.mainManifest,
                            params.manifestOverlays,
                            Collections.emptyList(),
                            params.navigationFiles,
                            null,
                            params.packageOverride,
                            params.versionCode,
                            params.versionName,
                            params.minSdkVersion,
                            params.targetSdkVersion,
                            params.maxSdkVersion,
                            params.manifestOutputFile.getAbsolutePath(),
                            params.aaptFriendlyManifestOutputFile != null
                                    ? params.aaptFriendlyManifestOutputFile.getAbsolutePath()
                                    : null,
                            null /* outInstantRunManifestLocation */,
                            null, /*outMetadataFeatureManifestLocation */
                            null /* outInstantAppManifestLocation */,
                            ManifestMerger2.MergeType.LIBRARY,
                            params.manifestPlaceholders,
                            optionalFeatures,
                            params.reportFile,
                            LoggerWrapper.getLogger(ProcessLibraryManifest.class));

            XmlDocument mergedXmlDocument =
                    mergingReport.getMergedXmlDocument(MergingReport.MergedManifestKind.MERGED);

            try {
                outputMergeBlameContents(mergingReport, params.mergeBlameFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            ImmutableMap<String, String> properties =
                    mergedXmlDocument != null
                            ? ImmutableMap.of(
                                    "packageId", mergedXmlDocument.getPackageName(),
                                    "split", mergedXmlDocument.getSplitName())
                            : ImmutableMap.of();

            try {
                if (params.manifestOutputDirectory != null) {
                    new BuildOutput(
                                    InternalArtifactType.MERGED_MANIFESTS,
                                    params.mainSplit,
                                    params.manifestOutputFile,
                                    properties)
                            .save(params.manifestOutputDirectory);
                }

                if (params.aaptFriendlyManifestOutputDirectory != null) {
                    new BuildOutput(
                                    InternalArtifactType.AAPT_FRIENDLY_MERGED_MANIFESTS,
                                    params.mainSplit,
                                    params.aaptFriendlyManifestOutputFile,
                                    properties)
                            .save(params.aaptFriendlyManifestOutputDirectory);
                }
            } catch (IOException e) {
                throw new BuildException("Exception while saving build metadata : ", e);
            }

        }
    }

    @Nullable
    @Override
    @Internal
    public File getAaptFriendlyManifestOutputFile() {
        Preconditions.checkNotNull(outputScope.getMainSplit());
        return getAaptFriendlyManifestOutputDirectory().isPresent()
                ? FileUtils.join(
                        getAaptFriendlyManifestOutputDirectory().get().getAsFile(),
                        outputScope.getMainSplit().getDirName(),
                        SdkConstants.ANDROID_MANIFEST_XML)
                : null;
    }

    @Input
    @Optional
    public String getMinSdkVersion() {
        return minSdkVersion.get();
    }

    @Input
    @Optional
    public String getTargetSdkVersion() {
        return targetSdkVersion.get();
    }

    @Input
    @Optional
    public Integer getMaxSdkVersion() {
        return maxSdkVersion.get();
    }

    @Internal
    public VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor>
            getVariantConfiguration() {
        return variantConfiguration;
    }

    public void setVariantConfiguration(
            VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor> variantConfiguration) {
        this.variantConfiguration = variantConfiguration;
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public File getMainManifest() {
        return variantConfiguration.getMainManifest();
    }

    @Input
    @Optional
    public String getPackageOverride() {
        return variantConfiguration.getApplicationId();
    }

    @Input
    public int getVersionCode() {
        return variantConfiguration.getVersionCode();
    }

    @Input
    @Optional
    @Nullable
    public String getVersionName() {
        return variantConfiguration.getVersionName();
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public List<File> getManifestOverlays() {
        return variantConfiguration.getManifestOverlays();
    }

    /**
     * Returns a serialized version of our map of key value pairs for placeholder substitution.
     *
     * This serialized form is only used by gradle to compare past and present tasks to determine
     * whether a task need to be re-run or not.
     */
    @Input
    @Optional
    public String getManifestPlaceholders() {
        return serializeMap(variantConfiguration.getManifestPlaceholders());
    }

    @Input
    public String getMainSplitFullName() {
        // This information is written to the build output's metadata file, so it needs to be
        // annotated as @Input
        return outputScope.getMainSplit().getFullName();
    }

    public static class CreationAction
            extends AnnotationProcessingTaskCreationAction<ProcessLibraryManifest> {

        VariantScope scope;
        private File reportFile;

        /**
         * {@code EagerTaskCreationAction} for the library process manifest task.
         *
         * @param scope The library variant scope.
         */
        public CreationAction(@NonNull VariantScope scope) {
            super(scope, scope.getTaskName("process", "Manifest"), ProcessLibraryManifest.class);
            this.scope = scope;
        }

        @Override
        public void preConfigure(@NonNull String taskName) {
            super.preConfigure(taskName);

            reportFile =
                    FileUtils.join(
                            getVariantScope().getGlobalScope().getOutputsDir(),
                            "logs",
                            "manifest-merger-"
                                    + getVariantScope().getVariantConfiguration().getBaseName()
                                    + "-report.txt");

            getVariantScope()
                    .getArtifacts()
                    .appendArtifact(
                            InternalArtifactType.MANIFEST_MERGE_REPORT,
                            ImmutableList.of(reportFile),
                            taskName);
        }

        @Override
        public void handleProvider(
                @NonNull TaskProvider<? extends ProcessLibraryManifest> taskProvider) {
            super.handleProvider(taskProvider);
            scope.getTaskContainer().setProcessManifestTask(taskProvider);

            scope.getArtifacts()
                    .producesDir(
                            InternalArtifactType.AAPT_FRIENDLY_MERGED_MANIFESTS,
                            BuildArtifactsHolder.OperationType.INITIAL,
                            taskProvider,
                            taskProvider.map(
                                    ManifestProcessorTask::getAaptFriendlyManifestOutputDirectory),
                            "aapt");

            scope.getArtifacts()
                    .producesDir(
                            InternalArtifactType.MERGED_MANIFESTS,
                            BuildArtifactsHolder.OperationType.INITIAL,
                            taskProvider,
                            taskProvider.map(ManifestProcessorTask::getManifestOutputDirectory),
                            "");

            scope.getArtifacts()
                    .producesFile(
                            InternalArtifactType.LIBRARY_MANIFEST,
                            BuildArtifactsHolder.OperationType.INITIAL,
                            taskProvider,
                            taskProvider.map(ProcessLibraryManifest::getManifestOutputFile),
                            SdkConstants.ANDROID_MANIFEST_XML);

            getVariantScope()
                    .getArtifacts()
                    .producesFile(
                            InternalArtifactType.MANIFEST_MERGE_BLAME_FILE,
                            BuildArtifactsHolder.OperationType.INITIAL,
                            taskProvider,
                            taskProvider.map(ProcessLibraryManifest::getMergeBlameFile),
                            "manifest-merger-blame-"
                                    + getVariantScope().getVariantConfiguration().getBaseName()
                                    + "-report.txt");
        }

        @Override
        public void configure(@NonNull ProcessLibraryManifest task) {
            super.configure(task);

            task.checkManifestResult =
                    getVariantScope()
                            .getArtifacts()
                            .getFinalArtifactFilesIfPresent(
                                    InternalArtifactType.CHECK_MANIFEST_RESULT);
            VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor> config =
                    getVariantScope().getVariantConfiguration();

            task.variantConfiguration = config;

            final ProductFlavor mergedFlavor = config.getMergedFlavor();

            task.minSdkVersion =
                    TaskInputHelper.memoize(
                            () -> {
                                ApiVersion minSdkVersion1 = mergedFlavor.getMinSdkVersion();
                                if (minSdkVersion1 == null) {
                                    return null;
                                }
                                return minSdkVersion1.getApiString();
                            });

            task.targetSdkVersion =
                    TaskInputHelper.memoize(
                            () -> {
                                ApiVersion targetSdkVersion = mergedFlavor.getTargetSdkVersion();
                                if (targetSdkVersion == null) {
                                    return null;
                                }
                                return targetSdkVersion.getApiString();
                            });

            task.maxSdkVersion = TaskInputHelper.memoize(mergedFlavor::getMaxSdkVersion);

            task.outputScope = getVariantScope().getOutputScope();

            task.setReportFile(reportFile);

            task.isNamespaced =
                    getVariantScope()
                            .getGlobalScope()
                            .getExtension()
                            .getAaptOptions()
                            .getNamespaced();
        }
    }
}
