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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.scope.BuildArtifactsHolder
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.android.build.gradle.internal.tasks.featuresplit.FeatureSetMetadata
import com.android.tools.build.libraries.metadata.AppDependencies
import com.android.tools.build.libraries.metadata.Library
import com.android.tools.build.libraries.metadata.LibraryDependencies
import com.android.tools.build.libraries.metadata.MavenLibrary
import com.android.tools.build.libraries.metadata.ModuleDependencies
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ComponentSelectionCause
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import java.io.FileOutputStream
import java.util.Dictionary
import java.util.Hashtable
import java.util.LinkedList
import java.util.function.Supplier
import javax.inject.Inject

/**
 * Task that publishes the app dependencies proto for each module.
 */
open class PerModuleReportDependenciesTask @Inject constructor(objectFactory: ObjectFactory) :
    NonIncrementalTask() {

    private lateinit var runtimeClasspath: Configuration

    @get:Input
    lateinit var runtimeClasspathArtifacts : FileCollection
        private set

    @get:OutputFile
    val dependenciesList: RegularFileProperty = objectFactory.fileProperty()

    private lateinit var moduleNameSupplier: Supplier<String>

    @get:Input
    val moduleName: String
        get() = moduleNameSupplier.get()

    private fun convertDependencyToMavenLibrary(
        moduleVersion: ModuleVersionIdentifier?,
        librariesToIndexMap: Dictionary<Library, Integer>,
        libraries: LinkedList<Library>
    ): Integer? {
        if (moduleVersion != null) {
            val lib = Library.newBuilder()
                .setMavenLibrary(MavenLibrary.newBuilder()
                    .setGroupId(moduleVersion.group)
                    .setArtifactId(moduleVersion.name)
                    .setVersion(moduleVersion.version)
                    .build())
                .build()
            var index = librariesToIndexMap.get(lib)
            if (index == null) {
                index = Integer(libraries.size)
                libraries.add(lib)
                librariesToIndexMap.put(lib, index)
            }
            return index
        }
        return null
    }

    override fun doTaskAction() {

        val librariesToIndexMap: Dictionary<Library, Integer> = Hashtable()
        val libraries = LinkedList<Library>()
        val libraryDependencies = LinkedList<LibraryDependencies>()
        val directDependenciesIndices: MutableSet<Integer> = HashSet()
        for (dependency in runtimeClasspath.incoming.resolutionResult.allDependencies) {
            // ignore non maven repository dependencies for now.
            if (dependency !is ResolvedDependencyResult
                || dependency.requested !is ModuleComponentSelector) {
                continue;
            }
            val resolvedComponent = dependency.selected
            val index = convertDependencyToMavenLibrary(
                resolvedComponent.moduleVersion,
                librariesToIndexMap,
                libraries)
            if (index != null) {
                // add library dependency if we haven't traversed it yet.
                if (libraryDependencies.filter { it.libraryIndex == index.toInt() }.isEmpty()) {
                    val libraryDependency =
                        LibraryDependencies.newBuilder().setLibraryIndex(index.toInt())
                    for (libDep in resolvedComponent.dependencies) {
                        if (libDep !is ResolvedDependencyResult
                            || libDep.requested !is ModuleComponentSelector) {
                            continue;
                        }
                        val depIndex = convertDependencyToMavenLibrary(
                            libDep.selected.moduleVersion,
                            librariesToIndexMap,
                            libraries
                        )
                        if (depIndex != null) {
                            libraryDependency.addLibraryDepIndex(depIndex.toInt())
                        }
                    }

                    libraryDependencies.add(libraryDependency.build())
                }

                if (dependency.from.selectionReason.descriptions.filter
                    {
                        it.cause == ComponentSelectionCause.ROOT
                    }.isNotEmpty()) {
                    // this is a direct module dependency.
                    directDependenciesIndices.add(index)
                }
            }
        }


        val moduleDependency = ModuleDependencies.newBuilder().setModuleName(moduleName)
        for (index in directDependenciesIndices) {
            moduleDependency.addDependencyIndex(index.toInt())
        }
        val appDependencies = AppDependencies.newBuilder()
            .addAllLibrary(libraries)
            .addAllLibraryDependencies(libraryDependencies)
            .addModuleDependencies(moduleDependency.build())
            .build()

        appDependencies.writeDelimitedTo(FileOutputStream(dependenciesList.get().asFile))
    }


    class CreationAction(
        variantScope: VariantScope
    ) : VariantTaskCreationAction<PerModuleReportDependenciesTask>(variantScope) {
        override val name: String = variantScope.getTaskName("collect", "Dependencies")
        override val type: Class<PerModuleReportDependenciesTask> = PerModuleReportDependenciesTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<out PerModuleReportDependenciesTask>) {
            super.handleProvider(taskProvider)

            variantScope
                .artifacts
                .producesFile(
                    InternalArtifactType.METADATA_LIBRARY_DEPENDENCIES_REPORT,
                    BuildArtifactsHolder.OperationType.INITIAL,
                    taskProvider,
                    taskProvider.map { task -> task.dependenciesList },
                    "dependencies.pb"
                )
        }

        override fun configure(task: PerModuleReportDependenciesTask) {
            super.configure(task)
            task.runtimeClasspath = variantScope.variantDependencies.runtimeClasspath
            task.runtimeClasspathArtifacts = variantScope.getArtifactCollection(
                AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                AndroidArtifacts.ArtifactScope.EXTERNAL,
                // Normally we would query for PROCESSED_JAR, but JAR is probably sufficient here
                // since this task is interested in only the meta data of the jar files.
                AndroidArtifacts.ArtifactType.JAR
            ).artifactFiles


            task.moduleNameSupplier = if (variantScope.type.isBaseModule)
                Supplier { "base" }
            else {
                val featureName: Supplier<String> = FeatureSetMetadata.getInstance()
                    .getFeatureNameSupplierForTask(variantScope, task)
                Supplier { "${featureName.get()}" }
            }
        }
    }
}
