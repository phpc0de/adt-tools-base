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

import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.ALL;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.AIDL;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.core.VariantConfiguration;
import com.android.build.gradle.internal.process.GradleProcessExecutor;
import com.android.build.gradle.internal.scope.InternalArtifactType;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.NonIncrementalTask;
import com.android.build.gradle.internal.tasks.Workers;
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction;
import com.android.builder.compiling.DependencyFileProcessor;
import com.android.builder.internal.compiler.AidlProcessor;
import com.android.builder.internal.compiler.DirectoryWalker;
import com.android.builder.internal.incremental.DependencyData;
import com.android.ide.common.process.LoggedProcessOutputHandler;
import com.android.ide.common.process.ProcessExecutor;
import com.android.ide.common.workers.WorkerExecutorFacade;
import com.android.utils.FileUtils;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.util.PatternSet;

/** Task to compile aidl files. Supports incremental update. */
@CacheableTask
public class AidlCompile extends NonIncrementalTask {

    private static final PatternSet PATTERN_SET = new PatternSet().include("**/*.aidl");

    private File sourceOutputDir;

    @Nullable
    private File packagedDir;

    @Nullable
    private Collection<String> packageWhitelist;

    private Supplier<Collection<File>> sourceDirs;
    private FileCollection importDirs;

    private Provider<File> aidlExecutableProvider;
    private Provider<File> aidlFrameworkProvider;

    private ProcessExecutor processExecutor;

    private WorkerExecutorFacade workers;

    /**
     * TODO(b/124424292)
     *
     * <p>We can not use gradle worker in this task as we use {@link GradleProcessExecutor} for
     * compiling aidl files, which should not be serialized.
     */
    public AidlCompile() {
        this.workers = Workers.INSTANCE.withThreads(getProject().getName(), getPath());
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public Provider<File> getAidlExecutableProvider() {
        return aidlExecutableProvider;
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public Provider<File> getAidlFrameworkProvider() {
        return aidlFrameworkProvider;
    }

    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getSourceFiles() {
        // this is because aidl may be in the same folder as Java and we want to restrict to
        // .aidl files and not java files.
        return getProject().files(sourceDirs.get()).getAsFileTree().matching(PATTERN_SET);
    }

    private static class DepFileProcessor implements DependencyFileProcessor {
        @Override
        public DependencyData processFile(@NonNull File dependencyFile) throws IOException {
            return DependencyData.parseDependencyFile(dependencyFile);
        }
    }

    @Override
    protected void doTaskAction() throws IOException {
        // this is full run, clean the previous output
        File destinationDir = getSourceOutputDir();
        File parcelableDir = getPackagedDir();
        FileUtils.cleanOutputDir(destinationDir);
        if (parcelableDir != null) {
            FileUtils.cleanOutputDir(parcelableDir);
        }

        try (WorkerExecutorFacade workers = this.workers) {
            Collection<File> sourceFolders = sourceDirs.get();
            Set<File> importFolders = getImportDirs().getFiles();

            List<File> fullImportList =
                    Lists.newArrayListWithCapacity(sourceFolders.size() + importFolders.size());
            fullImportList.addAll(sourceFolders);
            fullImportList.addAll(importFolders);

            AidlProcessor processor =
                    new AidlProcessor(
                            aidlExecutableProvider.get().getAbsolutePath(),
                            aidlFrameworkProvider.get().getAbsolutePath(),
                            fullImportList,
                            sourceOutputDir,
                            packagedDir,
                            packageWhitelist,
                            new DepFileProcessor(),
                            processExecutor,
                            new LoggedProcessOutputHandler(new LoggerWrapper(getLogger())));

            for (File dir : sourceFolders) {
                workers.submit(AidlCompileRunnable.class, new AidlCompileParams(dir, processor));
            }
        }
    }

    @OutputDirectory
    public File getSourceOutputDir() {
        return sourceOutputDir;
    }

    public void setSourceOutputDir(File sourceOutputDir) {
        this.sourceOutputDir = sourceOutputDir;
    }

    @OutputDirectory
    @Optional
    @Nullable
    public File getPackagedDir() {
        return packagedDir;
    }

    public void setPackagedDir(@Nullable File packagedDir) {
        this.packagedDir = packagedDir;
    }

    @Input
    @Optional
    @Nullable
    public Collection<String> getPackageWhitelist() {
        return packageWhitelist;
    }

    public void setPackageWhitelist(@Nullable Collection<String> packageWhitelist) {
        this.packageWhitelist = packageWhitelist;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getImportDirs() {
        return importDirs;
    }

    public static class CreationAction extends VariantTaskCreationAction<AidlCompile> {

        private File sourceOutputDir;
        private File packagedDir;

        public CreationAction(@NonNull VariantScope scope) {
            super(scope);
        }

        @Override
        @NonNull
        public String getName() {
            return getVariantScope().getTaskName("compile", "Aidl");
        }

        @Override
        @NonNull
        public Class<AidlCompile> getType() {
            return AidlCompile.class;
        }

        @Override
        public void preConfigure(@NonNull String taskName) {
            super.preConfigure(taskName);

            sourceOutputDir =
                    getVariantScope()
                            .getArtifacts()
                            .appendArtifact(
                                    InternalArtifactType.AIDL_SOURCE_OUTPUT_DIR, taskName, "out");
            if (getVariantScope().getVariantConfiguration().getType().isAar()) {
                packagedDir =
                        getVariantScope()
                                .getArtifacts()
                                .appendArtifact(
                                        InternalArtifactType.AIDL_PARCELABLE, taskName, "out");
            }
        }

        @Override
        public void handleProvider(@NonNull TaskProvider<? extends AidlCompile> taskProvider) {
            super.handleProvider(taskProvider);
            getVariantScope().getTaskContainer().setAidlCompileTask(taskProvider);
        }

        @Override
        public void configure(@NonNull AidlCompile compileTask) {
            super.configure(compileTask);
            VariantScope scope = getVariantScope();

            final VariantConfiguration<?, ?, ?> variantConfiguration = scope
                    .getVariantConfiguration();

            compileTask.aidlExecutableProvider =
                    scope.getGlobalScope().getSdkComponents().getAidlExecutableProvider();
            compileTask.aidlFrameworkProvider =
                    scope.getGlobalScope().getSdkComponents().getAidlFrameworkProvider();
            compileTask.processExecutor = scope.getGlobalScope().getProcessExecutor();

            compileTask.sourceDirs = variantConfiguration::getAidlSourceList;
            compileTask.importDirs = scope.getArtifactFileCollection(
                    COMPILE_CLASSPATH, ALL, AIDL);

            compileTask.setSourceOutputDir(sourceOutputDir);

            if (variantConfiguration.getType().isAar()) {
                compileTask.setPackagedDir(packagedDir);
                compileTask.setPackageWhitelist(
                        scope.getGlobalScope().getExtension().getAidlPackageWhiteList());
            }

        }
    }

    static class AidlCompileRunnable implements Runnable {
        private final AidlCompileParams params;

        @Inject
        AidlCompileRunnable(AidlCompileParams params) {
            this.params = params;
        }

        @Override
        public void run() {
            try {
                DirectoryWalker.builder()
                        .root(params.dir.toPath())
                        .extensions("aidl")
                        .action(params.processor)
                        .build()
                        .walk();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class AidlCompileParams implements Serializable {
        private final File dir;
        private final AidlProcessor processor;

        AidlCompileParams(File dir, AidlProcessor processor) {
            this.dir = dir;
            this.processor = processor;
        }
    }
}
