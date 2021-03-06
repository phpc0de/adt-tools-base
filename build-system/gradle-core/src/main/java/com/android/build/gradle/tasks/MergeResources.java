/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static com.android.build.gradle.internal.TaskManager.MergeType.MERGE;
import static com.android.build.gradle.internal.scope.InternalArtifactType.DATA_BINDING_LAYOUT_INFO_TYPE_MERGE;
import static com.android.build.gradle.internal.scope.InternalArtifactType.DATA_BINDING_LAYOUT_INFO_TYPE_PACKAGE;

import android.databinding.tool.LayoutXmlProcessor;
import android.databinding.tool.util.RelativizableFile;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.aapt.WorkerExecutorResourceCompilationService;
import com.android.build.gradle.internal.errors.MessageReceiverImpl;
import com.android.build.gradle.internal.res.Aapt2MavenUtils;
import com.android.build.gradle.internal.res.namespaced.Aapt2DaemonManagerService;
import com.android.build.gradle.internal.res.namespaced.Aapt2ServiceKey;
import com.android.build.gradle.internal.res.namespaced.NamespaceRemover;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.Blocks;
import com.android.build.gradle.internal.tasks.TaskInputHelper;
import com.android.build.gradle.internal.tasks.Workers;
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.SyncOptions;
import com.android.builder.model.SourceProvider;
import com.android.builder.model.VectorDrawablesOptions;
import com.android.builder.png.VectorDrawableRenderer;
import com.android.ide.common.blame.MergingLog;
import com.android.ide.common.resources.CopyToOutputDirectoryResourceCompilationService;
import com.android.ide.common.resources.FileStatus;
import com.android.ide.common.resources.FileValidity;
import com.android.ide.common.resources.GeneratedResourceSet;
import com.android.ide.common.resources.MergedResourceWriter;
import com.android.ide.common.resources.MergingException;
import com.android.ide.common.resources.NoOpResourcePreprocessor;
import com.android.ide.common.resources.ResourceCompilationService;
import com.android.ide.common.resources.ResourceMerger;
import com.android.ide.common.resources.ResourcePreprocessor;
import com.android.ide.common.resources.ResourceSet;
import com.android.ide.common.resources.SingleFileProcessor;
import com.android.ide.common.vectordrawable.ResourcesNotSupportedException;
import com.android.ide.common.workers.WorkerExecutorFacade;
import com.android.resources.Density;
import com.android.utils.FileUtils;
import com.android.utils.ILogger;
import com.google.common.collect.ImmutableSet;
import com.google.wireless.android.sdk.stats.GradleBuildProfileSpan;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.xml.bind.JAXBException;
import kotlin.Pair;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.workers.WorkerExecutor;

@CacheableTask
public abstract class MergeResources extends ResourceAwareTask {
    // ----- PUBLIC TASK API -----

    /**
     * Directory to write the merged resources to
     */
    private File outputDir;

    private File generatedPngsOutputDir;

    // ----- PRIVATE TASK API -----

    /**
     * Optional file to write any publicly imported resource types and names to
     */
    private File publicFile;

    private boolean processResources;

    private boolean crunchPng;

    private File blameLogFolder;

    // file inputs as raw files, lazy behind a memoized/bypassed supplier
    private Supplier<Collection<File>> sourceFolderInputs;

    private List<ResourceSet> processedInputs;

    private final FileValidity<ResourceSet> fileValidity = new FileValidity<>();

    private boolean disableVectorDrawables;

    private boolean vectorSupportLibraryIsUsed;

    private Collection<String> generatedDensities;

    private Supplier<Integer> minSdk;

    private String aapt2Version;

    @Internal
    public abstract ConfigurableFileCollection getAapt2FromMaven();

    @Nullable private SingleFileProcessor dataBindingLayoutProcessor;

    /** Where data binding exports its outputs after parsing layout files. */
    @Nullable private File dataBindingLayoutInfoOutFolder;

    @Nullable private File mergedNotCompiledResourcesOutputDirectory;

    private boolean pseudoLocalesEnabled;

    private boolean precompileRemoteResources;

    private ImmutableSet<Flag> flags;

    @NonNull
    private static ResourceCompilationService getResourceProcessor(
            @Nullable FileCollection aapt2FromMaven,
            @NonNull WorkerExecutorFacade workerExecutor,
            SyncOptions.ErrorFormatMode errorFormatMode,
            ImmutableSet<Flag> flags,
            boolean processResources,
            Logger logger) {
        // If we received the flag for removing namespaces we need to use the namespace remover to
        // process the resources.
        if (flags.contains(Flag.REMOVE_RESOURCE_NAMESPACES)) {
            return NamespaceRemover.INSTANCE;
        }

        // If we're not removing namespaces and there's no need to compile the resources, return a
        // no-op resource processor.
        if (!processResources) {
            return CopyToOutputDirectoryResourceCompilationService.INSTANCE;
        }

        Aapt2ServiceKey aapt2ServiceKey =
                Aapt2DaemonManagerService.registerAaptService(
                        aapt2FromMaven, new LoggerWrapper(logger));

        return new WorkerExecutorResourceCompilationService(
                workerExecutor, aapt2ServiceKey, errorFormatMode);
    }

    @Override
    protected boolean getIncremental() {
        return true;
    }

    @Nullable
    @OutputDirectory
    @Optional
    public File getDataBindingLayoutInfoOutFolder() {
        return dataBindingLayoutInfoOutFolder;
    }

    private final WorkerExecutorFacade workerExecutorFacade;

    private SyncOptions.ErrorFormatMode errorFormatMode;

    @Inject
    public MergeResources(WorkerExecutor workerExecutor) {
        this.workerExecutorFacade =
                Workers.INSTANCE.getWorkerForAapt2(
                        getProject().getName(), getPath(), workerExecutor);
    }

    @Override
    protected void doFullTaskAction() throws IOException, JAXBException {
        ResourcePreprocessor preprocessor = getPreprocessor();

        // this is full run, clean the previous outputs
        File destinationDir = getOutputDir();
        FileUtils.cleanOutputDir(destinationDir);
        if (dataBindingLayoutInfoOutFolder != null) {
            FileUtils.deleteDirectoryContents(dataBindingLayoutInfoOutFolder);
        }

        List<ResourceSet> resourceSets = getConfiguredResourceSets(preprocessor);

        // create a new merger and populate it with the sets.
        ResourceMerger merger = new ResourceMerger(minSdk.get());
        MergingLog mergingLog = null;
        if (blameLogFolder != null) {
            FileUtils.cleanOutputDir(blameLogFolder);
            mergingLog = new MergingLog(blameLogFolder);
        }

        try (ResourceCompilationService resourceCompiler =
                getResourceProcessor(
                        getAapt2FromMaven(),
                        workerExecutorFacade,
                        errorFormatMode,
                        flags,
                        processResources,
                        getLogger())) {

            Blocks.recordSpan(
                    getProject().getName(),
                    getPath(),
                    GradleBuildProfileSpan.ExecutionType.TASK_EXECUTION_PHASE_1,
                    () -> {
                        for (ResourceSet resourceSet : resourceSets) {
                            resourceSet.loadFromFiles(new LoggerWrapper(getLogger()));
                            merger.addDataSet(resourceSet);
                        }
                    });

            MergedResourceWriter writer =
                    new MergedResourceWriter(
                            workerExecutorFacade,
                            destinationDir,
                            getPublicFile(),
                            mergingLog,
                            preprocessor,
                            resourceCompiler,
                            getIncrementalFolder(),
                            dataBindingLayoutProcessor,
                            mergedNotCompiledResourcesOutputDirectory,
                            pseudoLocalesEnabled,
                            getCrunchPng());

            Blocks.recordSpan(
                    getProject().getName(),
                    getPath(),
                    GradleBuildProfileSpan.ExecutionType.TASK_EXECUTION_PHASE_2,
                    () -> merger.mergeData(writer, false /*doCleanUp*/));

            Blocks.recordSpan(
                    getProject().getName(),
                    getPath(),
                    GradleBuildProfileSpan.ExecutionType.TASK_EXECUTION_PHASE_3,
                    () -> {
                        if (dataBindingLayoutProcessor != null) {
                            dataBindingLayoutProcessor.end();
                        }
                    });

            // No exception? Write the known state.
            Blocks.recordSpan(
                    getProject().getName(),
                    getPath(),
                    GradleBuildProfileSpan.ExecutionType.TASK_EXECUTION_PHASE_4,
                    () -> merger.writeBlobTo(getIncrementalFolder(), writer, false));

        } catch (Exception e) {
            MergingException.findAndReportMergingException(
                    e, new MessageReceiverImpl(errorFormatMode, getLogger()));
            try {
                throw e;
            } catch (MergingException mergingException) {
                merger.cleanBlob(getIncrementalFolder());
                throw new ResourceException(mergingException.getMessage(), mergingException);
            }
        } finally {
            cleanup();
        }
    }

    @Override
    protected void doIncrementalTaskAction(@NonNull Map<File, ? extends FileStatus> changedInputs)
            throws IOException, JAXBException {
        ResourcePreprocessor preprocessor = getPreprocessor();

        // create a merger and load the known state.
        ResourceMerger merger = new ResourceMerger(minSdk.get());
        try {
            if (!merger.loadFromBlob(getIncrementalFolder(), true /*incrementalState*/)) {
                doFullTaskAction();
                return;
            }

            for (ResourceSet resourceSet : merger.getDataSets()) {
                resourceSet.setPreprocessor(preprocessor);
            }

            List<ResourceSet> resourceSets = getConfiguredResourceSets(preprocessor);

            // compare the known state to the current sets to detect incompatibility.
            // This is in case there's a change that's too hard to do incrementally. In this case
            // we'll simply revert to full build.
            if (!merger.checkValidUpdate(resourceSets)) {
                getLogger().info("Changed Resource sets: full task run!");
                doFullTaskAction();
                return;
            }

            // The incremental process is the following:
            // Loop on all the changed files, find which ResourceSet it belongs to, then ask
            // the resource set to update itself with the new file.
            for (Map.Entry<File, ? extends FileStatus> entry : changedInputs.entrySet()) {
                File changedFile = entry.getKey();

                merger.findDataSetContaining(changedFile, fileValidity);
                if (fileValidity.getStatus() == FileValidity.FileStatus.UNKNOWN_FILE) {
                    doFullTaskAction();
                    return;
                } else if (fileValidity.getStatus() == FileValidity.FileStatus.VALID_FILE) {
                    if (!fileValidity
                            .getDataSet()
                            .updateWith(
                                    fileValidity.getSourceFile(),
                                    changedFile,
                                    entry.getValue(),
                                    new LoggerWrapper(getLogger()))) {
                        getLogger().info(
                                String.format("Failed to process %s event! Full task run",
                                        entry.getValue()));
                        doFullTaskAction();
                        return;
                    }
                }
            }

            MergingLog mergingLog =
                    getBlameLogFolder() != null ? new MergingLog(getBlameLogFolder()) : null;

            try (ResourceCompilationService resourceCompiler =
                    getResourceProcessor(
                            getAapt2FromMaven(),
                            workerExecutorFacade,
                            errorFormatMode,
                            flags,
                            processResources,
                            getLogger())) {

                MergedResourceWriter writer =
                        new MergedResourceWriter(
                                workerExecutorFacade,
                                getOutputDir(),
                                getPublicFile(),
                                mergingLog,
                                preprocessor,
                                resourceCompiler,
                                getIncrementalFolder(),
                                dataBindingLayoutProcessor,
                                mergedNotCompiledResourcesOutputDirectory,
                                pseudoLocalesEnabled,
                                getCrunchPng());

                merger.mergeData(writer, false /*doCleanUp*/);

                if (dataBindingLayoutProcessor != null) {
                    dataBindingLayoutProcessor.end();
                }

                // No exception? Write the known state.
                merger.writeBlobTo(getIncrementalFolder(), writer, false);
            }
        } catch (Exception e) {
            MergingException.findAndReportMergingException(
                    e, new MessageReceiverImpl(errorFormatMode, getLogger()));
            try {
                throw e;
            } catch (MergingException mergingException) {
                merger.cleanBlob(getIncrementalFolder());
                throw new ResourceException(mergingException.getMessage(), mergingException);
            }
        } finally {
            cleanup();
        }
    }

    private static class MergeResourcesVectorDrawableRenderer extends VectorDrawableRenderer {

        public MergeResourcesVectorDrawableRenderer(
                int minSdk,
                boolean supportLibraryIsUsed,
                File outputDir,
                Collection<Density> densities,
                Supplier<ILogger> loggerSupplier) {
            super(minSdk, supportLibraryIsUsed, outputDir, densities, loggerSupplier);
        }

        @Override
        public void generateFile(@NonNull File toBeGenerated, @NonNull File original)
                throws IOException {
            try {
                super.generateFile(toBeGenerated, original);
            } catch (ResourcesNotSupportedException e) {
                // Add gradle-specific error message.
                throw new GradleException(
                        String.format(
                                "Can't process attribute %1$s=\"%2$s\": "
                                        + "references to other resources are not supported by "
                                        + "build-time PNG generation.\n"
                                        + "%3$s\n"
                                        + "See http://developer.android.com/tools/help/vector-asset-studio.html "
                                        + "for details.",
                                e.getName(),
                                e.getValue(),
                                getPreprocessingReasonDescription(original)));
            }
        }
    }

    /**
     * Only one pre-processor for now. The code will need slight changes when we add more.
     */
    @NonNull
    private ResourcePreprocessor getPreprocessor() {
        if (disableVectorDrawables) {
            // If the user doesn't want any PNGs, leave the XML file alone as well.
            return NoOpResourcePreprocessor.INSTANCE;
        }

        Collection<Density> densities =
                getGeneratedDensities().stream().map(Density::getEnum).collect(Collectors.toList());

        return new MergeResourcesVectorDrawableRenderer(
                minSdk.get(),
                vectorSupportLibraryIsUsed,
                generatedPngsOutputDir,
                densities,
                LoggerWrapper.supplierFor(MergeResources.class));
    }

    @NonNull
    private List<ResourceSet> getConfiguredResourceSets(ResourcePreprocessor preprocessor) {
        // It is possible that this get called twice in case the incremental run fails and reverts
        // back to full task run. Because the cached ResourceList is modified we don't want
        // to recompute this twice (plus, why recompute it twice anyway?)
        if (processedInputs == null) {
            processedInputs = getResourcesComputer().compute(precompileRemoteResources);
            List<ResourceSet> generatedSets = new ArrayList<>(processedInputs.size());

            for (ResourceSet resourceSet : processedInputs) {
                resourceSet.setPreprocessor(preprocessor);
                ResourceSet generatedSet = new GeneratedResourceSet(resourceSet);
                resourceSet.setGeneratedSet(generatedSet);
                generatedSets.add(generatedSet);
            }

            // We want to keep the order of the inputs. Given inputs:
            // (A, B, C, D)
            // We want to get:
            // (A-generated, A, B-generated, B, C-generated, C, D-generated, D).
            // Therefore, when later in {@link DataMerger} we look for sources going through the
            // list backwards, B-generated will take priority over A (but not B).
            // A real life use-case would be if an app module generated resource overrode a library
            // module generated resource (existing not in generated but bundled dir at this stage):
            // (lib, app debug, app main)
            // We will get:
            // (lib generated, lib, app debug generated, app debug, app main generated, app main)
            for (int i = 0; i < generatedSets.size(); ++i) {
                processedInputs.add(2 * i, generatedSets.get(i));
            }
        }

        return processedInputs;
    }

    /**
     * Releases resource sets not needed anymore, otherwise they will waste heap space for the
     * duration of the build.
     *
     * <p>This might be called twice when an incremental build falls back to a full one.
     */
    private void cleanup() {
        fileValidity.clear();
        processedInputs = null;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public Collection<File> getSourceFolderInputs() {
        return sourceFolderInputs.get();
    }

    @OutputDirectory
    public File getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(File outputDir) {
        this.outputDir = outputDir;
    }

    @Input
    public boolean getCrunchPng() {
        return crunchPng;
    }

    @Input
    public boolean getProcessResources() {
        return processResources;
    }

    @Optional
    @OutputFile
    public File getPublicFile() {
        return publicFile;
    }

    public void setPublicFile(File publicFile) {
        this.publicFile = publicFile;
    }

    // Synthetic input: the validation flag is set on the resource sets in CreationAction.execute.
    @Input
    public boolean isValidateEnabled() {
        return getResourcesComputer().getValidateEnabled();
    }

    @OutputDirectory
    @Optional
    public File getBlameLogFolder() {
        return blameLogFolder;
    }

    public void setBlameLogFolder(File blameLogFolder) {
        this.blameLogFolder = blameLogFolder;
    }

    @Optional
    @OutputDirectory
    public File getGeneratedPngsOutputDir() {
        return generatedPngsOutputDir;
    }

    @Input
    public Collection<String> getGeneratedDensities() {
        return generatedDensities;
    }

    @Input
    public int getMinSdk() {
        return minSdk.get();
    }

    @Input
    public boolean isVectorSupportLibraryUsed() {
        return vectorSupportLibraryIsUsed;
    }

    @Input
    public String getAapt2Version() {
        return aapt2Version;
    }

    @Nullable
    @OutputDirectory
    @Optional
    public File getMergedNotCompiledResourcesOutputDirectory() {
        return mergedNotCompiledResourcesOutputDirectory;
    }

    @Input
    public boolean isPseudoLocalesEnabled() {
        return pseudoLocalesEnabled;
    }

    @Input
    public String getFlags() {
        return flags.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public static class CreationAction extends VariantTaskCreationAction<MergeResources> {
        @NonNull private final TaskManager.MergeType mergeType;
        @NonNull
        private final String taskNamePrefix;
        @Nullable
        private final File outputLocation;
        @Nullable private final File mergedNotCompiledOutputDirectory;
        private final boolean includeDependencies;
        private final boolean processResources;
        private final boolean processVectorDrawables;
        @NonNull private final ImmutableSet<Flag> flags;
        private File dataBindingLayoutInfoOutFolder;

        public CreationAction(
                @NonNull VariantScope variantScope,
                @NonNull TaskManager.MergeType mergeType,
                @NonNull String taskNamePrefix,
                @Nullable File outputLocation,
                @Nullable File mergedNotCompiledOutputDirectory,
                boolean includeDependencies,
                boolean processResources,
                @NonNull ImmutableSet<Flag> flags) {
            super(variantScope);
            this.mergeType = mergeType;
            this.taskNamePrefix = taskNamePrefix;
            this.outputLocation = outputLocation;
            this.mergedNotCompiledOutputDirectory = mergedNotCompiledOutputDirectory;
            this.includeDependencies = includeDependencies;
            this.processResources = processResources;
            this.processVectorDrawables = flags.contains(Flag.PROCESS_VECTOR_DRAWABLES);
            this.flags = flags;
        }

        @NonNull
        @Override
        public String getName() {
            return getVariantScope().getTaskName(taskNamePrefix, "Resources");
        }

        @NonNull
        @Override
        public Class<MergeResources> getType() {
            return MergeResources.class;
        }

        @Override
        public void preConfigure(@NonNull String taskName) {
            super.preConfigure(taskName);

            if (getVariantScope().getGlobalScope().getExtension().getDataBinding().isEnabled()) {
                // Keep as an output.
                dataBindingLayoutInfoOutFolder =
                        getVariantScope()
                                .getArtifacts()
                                .appendArtifact(
                                        mergeType == MERGE
                                                ? DATA_BINDING_LAYOUT_INFO_TYPE_MERGE
                                                : DATA_BINDING_LAYOUT_INFO_TYPE_PACKAGE,
                                        taskName,
                                        "out");
            }
        }

        @Override
        public void handleProvider(@NonNull TaskProvider<? extends MergeResources> taskProvider) {
            super.handleProvider(taskProvider);
            // In LibraryTaskManager#createMergeResourcesTasks, there are actually two
            // MergeResources tasks sharing the same task type (MergeResources) and CreationAction
            // code: packageResources with mergeType == PACKAGE, and mergeResources with
            // mergeType == MERGE. Since the following line of code is called for each task, the
            // latter one wins: The mergeResources task with mergeType == MERGE is the one that is
            // finally registered in the current scope.
            // Filed https://issuetracker.google.com//110412851 to clean this up at some point.
            getVariantScope().getTaskContainer().setMergeResourcesTask(taskProvider);
        }

        @Override
        public void configure(@NonNull MergeResources task) {
            super.configure(task);

            VariantScope variantScope = getVariantScope();
            GlobalScope globalScope = variantScope.getGlobalScope();
            BaseVariantData variantData = variantScope.getVariantData();

            task.minSdk =
                    TaskInputHelper.memoize(
                            () ->
                                    variantData
                                            .getVariantConfiguration()
                                            .getMinSdkVersion()
                                            .getApiLevel());

            Pair<FileCollection, String> aapt2AndVersion =
                    Aapt2MavenUtils.getAapt2FromMavenAndVersion(globalScope);
            task.getAapt2FromMaven().from(aapt2AndVersion.getFirst());
            task.aapt2Version = aapt2AndVersion.getSecond();
            task.setIncrementalFolder(variantScope.getIncrementalDir(getName()));
            // Libraries use this task twice, once for compilation (with dependencies),
            // where blame is useful, and once for packaging where it is not.
            if (includeDependencies) {
                task.setBlameLogFolder(variantScope.getResourceBlameLogDir());
            }
            task.processResources = processResources;
            task.crunchPng = variantScope.isCrunchPngs();

            VectorDrawablesOptions vectorDrawablesOptions = variantData
                    .getVariantConfiguration()
                    .getMergedFlavor()
                    .getVectorDrawables();
            task.generatedDensities = vectorDrawablesOptions.getGeneratedDensities();
            if (task.generatedDensities == null) {
                task.generatedDensities = Collections.emptySet();
            }

            task.disableVectorDrawables =
                    !processVectorDrawables || task.generatedDensities.isEmpty();

            // TODO: When support library starts supporting gradients (http://b/62421666), remove
            // the vectorSupportLibraryIsUsed field and set disableVectorDrawables when
            // the getUseSupportLibrary method returns TRUE.
            task.vectorSupportLibraryIsUsed =
                    Boolean.TRUE.equals(vectorDrawablesOptions.getUseSupportLibrary());

            task.getResourcesComputer().initFromVariantScope(variantScope, includeDependencies);
            task.sourceFolderInputs =
                    () ->
                            variantData
                                    .getVariantConfiguration()
                                    .getSourceFiles(SourceProvider::getResDirectories);

            task.outputDir = outputLocation;
            if (!task.disableVectorDrawables) {
                task.generatedPngsOutputDir = variantScope.getGeneratedPngsOutputDir();
            }

            if (globalScope.getExtension().getDataBinding().isEnabled()) {
                // Keep as an output.
                task.dataBindingLayoutInfoOutFolder = dataBindingLayoutInfoOutFolder;
                task.dataBindingLayoutProcessor =
                        new SingleFileProcessor() {

                            // Lazily instantiate the processor to avoid parsing the manifest.
                            private LayoutXmlProcessor processor;

                            private LayoutXmlProcessor getProcessor() {
                                if (processor == null) {
                                    processor = variantData.getLayoutXmlProcessor();
                                }
                                return processor;
                            }

                            @Override
                            public boolean processSingleFile(File file, File out) throws Exception {
                                // For cache relocatability, we want to pass relative paths here
                                // instead of absolute paths. However, it is currently not possible
                                // due to bug 128579779.
                                return getProcessor()
                                        .processSingleFile(
                                                RelativizableFile.fromAbsoluteFile(file, null),
                                                out);
                            }

                            @Override
                            public void processRemovedFile(File file) {
                                getProcessor().processRemovedFile(file);
                            }

                            @Override
                            public void end() throws JAXBException {
                                getProcessor()
                                        .writeLayoutInfoFiles(task.dataBindingLayoutInfoOutFolder);
                            }
                        };
            }

            task.mergedNotCompiledResourcesOutputDirectory = mergedNotCompiledOutputDirectory;

            task.pseudoLocalesEnabled =
                    variantScope
                            .getVariantData()
                            .getVariantConfiguration()
                            .getBuildType()
                            .isPseudoLocalesEnabled();
            task.flags = flags;

            task.errorFormatMode = SyncOptions.getErrorFormatMode(globalScope.getProjectOptions());

            task.precompileRemoteResources =
                    globalScope.getProjectOptions().get(BooleanOption.PRECOMPILE_REMOTE_RESOURCES);

            task.dependsOn(variantScope.getTaskContainer().getResourceGenTask());

        }
    }

    public enum Flag {
        REMOVE_RESOURCE_NAMESPACES,
        PROCESS_VECTOR_DRAWABLES,
    }
}
