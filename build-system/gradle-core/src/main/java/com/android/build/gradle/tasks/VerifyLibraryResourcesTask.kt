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

package com.android.build.gradle.tasks

import com.google.common.annotations.VisibleForTesting
import com.android.build.api.artifact.BuildableArtifact
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.api.artifact.singleFile
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.res.Aapt2CompileRunnable
import com.android.build.gradle.internal.res.Aapt2ProcessResourcesRunnable
import com.android.build.gradle.internal.res.getAapt2FromMavenAndVersion
import com.android.build.gradle.internal.res.namespaced.Aapt2ServiceKey
import com.android.build.gradle.internal.res.namespaced.registerAaptService
import com.android.build.gradle.internal.scope.ExistingBuildElements
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.tasks.IncrementalTask
import com.android.build.gradle.internal.tasks.Workers
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.android.build.gradle.internal.utils.toImmutableList
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.SyncOptions
import com.android.builder.core.VariantTypeImpl
import com.android.builder.internal.aapt.AaptException
import com.android.builder.internal.aapt.AaptOptions
import com.android.builder.internal.aapt.AaptPackageConfig
import com.android.builder.internal.aapt.v2.Aapt2RenamingConventions
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.resources.FileStatus
import com.android.ide.common.workers.WorkerExecutorFacade
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import java.util.function.Function as JavaFunction

@CacheableTask
abstract class VerifyLibraryResourcesTask @Inject
constructor(workerExecutor: WorkerExecutor) : IncrementalTask() {

    @get:OutputDirectory
    lateinit var compiledDirectory: File private set

    // Merged resources directory.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    lateinit var inputDirectory: BuildableArtifact private set

    lateinit var taskInputType: InternalArtifactType private set

    @Input
    fun getTaskInputType(): String {
        return taskInputType.name
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    lateinit var manifestFiles: Provider<Directory>
        private set

    @get:Input
    lateinit var aapt2Version: String
        private set
    @get:Internal
    abstract val aapt2FromMaven: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    lateinit var androidJar: Provider<File>
        private set

    private var compiledRemoteResources: ArtifactCollection? = null

    private lateinit var mergeBlameFolder: File

    private lateinit var manifestMergeBlameFile: Provider<RegularFile>

    private lateinit var errorFormatMode: SyncOptions.ErrorFormatMode

    private val workers: WorkerExecutorFacade = Workers.preferWorkers(project.name, path, workerExecutor)

    override val incremental: Boolean
        get() = true

    override fun doFullTaskAction() {
        // Mark all files as NEW and continue with the verification.
        val fileStatusMap = HashMap<File, FileStatus>()

        inputDirectory.singleFile().listFiles()
                .filter { it.isDirectory}
                .forEach { dir ->
                    dir.listFiles()
                            .filter { file -> Files.isRegularFile(file.toPath()) }
                            .forEach { file -> fileStatusMap[file] = FileStatus.NEW }
                }

        FileUtils.cleanOutputDir(compiledDirectory)
        compileAndVerifyResources(fileStatusMap)
    }

    override fun doIncrementalTaskAction(changedInputs: Map<File, FileStatus>) {
        compileAndVerifyResources(changedInputs)
    }

    /**
     * Compiles and links the resources of the library.
     *
     * @param inputs the new, changed or modified files that need to be compiled or removed.
     */
    private fun compileAndVerifyResources(inputs: Map<File, FileStatus>) {

        val manifestsOutputs = ExistingBuildElements.from(taskInputType, manifestFiles.get().asFile)
        val manifestFile = Iterables.getOnlyElement(manifestsOutputs).outputFile

        val aapt2ServiceKey = registerAaptService(aapt2FromMaven, LoggerWrapper(logger))
        // If we're using AAPT2 we need to compile the resources into the compiled directory
        // first as we need the .flat files for linking.
        workers.use { facade ->
            compileResources(
                inputs,
                compiledDirectory,
                facade,
                aapt2ServiceKey,
                inputDirectory.singleFile(),
                errorFormatMode,
                mergeBlameFolder
            )
            val config = getAaptPackageConfig(compiledDirectory, manifestFile)
            val params = Aapt2ProcessResourcesRunnable.Params(
                aapt2ServiceKey,
                config,
                errorFormatMode,
                mergeBlameFolder,
                manifestMergeBlameFile.orNull?.asFile
            )
            facade.submit(Aapt2ProcessResourcesRunnable::class.java, params)
        }

    }

    private fun getAaptPackageConfig(resDir: File, manifestFile: File): AaptPackageConfig {
        val compiledRemoteResourcesDirs =
            if (getCompiledRemoteResources() == null) emptyList<File>() else {
                // the order of the artifact is descending order, so we need to reverse it.
                getCompiledRemoteResources()!!.reversed().toImmutableList()
            }

        // We're do not want to generate any files - only to make sure everything links properly.
        return AaptPackageConfig.Builder()
            .setManifestFile(manifestFile)
            .addResourceDirectories(compiledRemoteResourcesDirs)
            .addResourceDir(resDir)
            .setLibrarySymbolTableFiles(ImmutableSet.of())
            .setOptions(AaptOptions(failOnMissingConfigEntry = false))
            .setVariantType(VariantTypeImpl.LIBRARY)
            .setAndroidTarget(androidJar.get())
            .build()
    }

    class CreationAction(
        variantScope: VariantScope
    ) : VariantTaskCreationAction<VerifyLibraryResourcesTask>(variantScope) {

        override val name: String
            get() = variantScope.getTaskName("verify", "Resources")
        override val type: Class<VerifyLibraryResourcesTask>
            get() = VerifyLibraryResourcesTask::class.java

        /** Configure the given newly-created task object.  */
        override fun configure(task: VerifyLibraryResourcesTask) {
            super.configure(task)

            val (aapt2FromMaven, aapt2Version) = getAapt2FromMavenAndVersion(variantScope.globalScope)
            task.aapt2FromMaven.from(aapt2FromMaven)
            task.aapt2Version = aapt2Version
            task.incrementalFolder = variantScope.getIncrementalDir(name)

            task.inputDirectory =
                    variantScope.artifacts.getFinalArtifactFiles(InternalArtifactType.MERGED_RES)

            task.compiledDirectory = variantScope.compiledResourcesOutputDir

            val aaptFriendlyManifestsFilePresent = variantScope.artifacts
                    .hasFinalProduct(InternalArtifactType.AAPT_FRIENDLY_MERGED_MANIFESTS)
            task.taskInputType = when {
                aaptFriendlyManifestsFilePresent ->
                    InternalArtifactType.AAPT_FRIENDLY_MERGED_MANIFESTS
                else ->
                    InternalArtifactType.MERGED_MANIFESTS
            }
            task.manifestFiles = variantScope.artifacts
                    .getFinalProduct(task.taskInputType)

            task.androidJar = variantScope.globalScope.sdkComponents.androidJarProvider

            task.mergeBlameFolder = variantScope.resourceBlameLogDir

            task.manifestMergeBlameFile = variantScope.artifacts.getFinalProduct(
                InternalArtifactType.MANIFEST_MERGE_BLAME_FILE
            )

            task.errorFormatMode = SyncOptions.getErrorFormatMode(
                variantScope.globalScope.projectOptions
            )

            if (variantScope.globalScope.projectOptions.get(BooleanOption.PRECOMPILE_REMOTE_RESOURCES)) {
                task.compiledRemoteResources =
                    variantScope.getArtifactCollection(
                        AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                        AndroidArtifacts.ArtifactScope.ALL,
                        AndroidArtifacts.ArtifactType.COMPILED_REMOTE_RESOURCES
                    )
            }
        }
    }

    /**
     * Returns a file collection of the directories containing the compiled remote libraries
     * resource files.
     */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getCompiledRemoteResources(): FileCollection? {
        return compiledRemoteResources?.artifactFiles
    }

    companion object {
        /**
         * Compiles new or changed files and removes files that were compiled from the removed files.
         *
         *
         * Should only be called when using AAPT2.
         *
         * @param inputs the new, changed or modified files that need to be compiled or removed.
         * @param outDirectory the directory containing compiled resources.
         * @param aapt AAPT tool to execute the resource compiling, either must be supplied or
         * worker executor and revision must be supplied.
         * @param aapt2ServiceKey the AAPT2 service to inject in to the worker executor.
         * @param workerExecutor the worker executor to submit AAPT compilations to.
         * @param mergedResDirectory directory containing merged uncompiled resources.
         */
        @JvmStatic
        @VisibleForTesting
        fun compileResources(
            inputs: Map<File, FileStatus>,
            outDirectory: File,
            workerExecutor: WorkerExecutorFacade,
            aapt2ServiceKey: Aapt2ServiceKey,
            mergedResDirectory: File,
            errorFormatMode: SyncOptions.ErrorFormatMode,
            mergeBlameFolder: File
        ) {

            for ((key, value) in inputs) {
                // Accept only files in subdirectories of the merged resources directory.
                // Ignore files and directories directly under the merged resources directory.
                if (key.parentFile.parentFile != mergedResDirectory) continue
                when (value) {
                    FileStatus.NEW, FileStatus.CHANGED ->
                        // If the file is NEW or CHANGED we need to compile it into the output
                        // directory. AAPT2 overwrites files in case they were CHANGED so no need to
                        // remove the corresponding file.
                        try {
                            val request = CompileResourceRequest(
                                key,
                                outDirectory,
                                key.parent,
                                isPseudoLocalize = false,
                                isPngCrunching = false,
                                mergeBlameFolder = mergeBlameFolder
                            )
                            workerExecutor.submit(
                                Aapt2CompileRunnable::class.java,
                                Aapt2CompileRunnable.Params(
                                    aapt2ServiceKey,
                                    listOf(request),
                                    errorFormatMode,
                                    true
                                )
                            )
                        } catch (e: Exception) {
                            throw AaptException("Failed to compile file ${key.absolutePath}", e)
                        }

                    FileStatus.REMOVED ->
                        // If the file was REMOVED we need to remove the corresponding file from the
                        // output directory.
                        FileUtils.deleteIfExists(
                                File(outDirectory,Aapt2RenamingConventions.compilationRename(key)))
                }
            }
            // We need to wait for the files to finish compiling before we do the link.
            workerExecutor.await()
        }
    }
}
