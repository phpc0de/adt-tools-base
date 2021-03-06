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

package com.android.build.gradle.internal.res.namespaced

import com.android.build.gradle.internal.scope.BuildArtifactsHolder
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.tasks.NonIncrementalTask
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.android.builder.symbols.exportToCompiledJava
import com.android.ide.common.symbols.SymbolTable
import com.android.utils.FileUtils
import com.google.common.base.Suppliers
import com.google.common.collect.ImmutableList
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.function.Supplier
import javax.inject.Inject

/*
 * Class generating the R.jar and res-ids.txt files for a resource namespace aware library.
 */
@CacheableTask
open class GenerateNamespacedLibraryRFilesTask @Inject constructor(objects: ObjectFactory) : NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    lateinit var partialRFiles: Provider<List<Directory>>
        private set

    @get:Internal
    lateinit var packageForRSupplier: Supplier<String>
        private set
    @get:Input
    val packageForR
        get() = packageForRSupplier.get()

    @get:OutputFile
    val rJarFile: RegularFileProperty= objects.fileProperty()

    override fun doTaskAction() {
        // Keeping the order is important.
        val partialRFiles = ImmutableList.builder<File>()
        this.partialRFiles.get().forEach { directory ->
           partialRFiles.addAll(directory.asFile.listFiles{ f -> f.isFile }.asIterable())
        }

        FileUtils.deleteIfExists(rJarFile.get().asFile)

        // Read the symbol tables from the partial R.txt files and merge them into one.
        val resources = SymbolTable.mergePartialTables(partialRFiles.build(), packageForR)

        // Generate the R.jar file containing compiled R class and its' inner classes.
        exportToCompiledJava(ImmutableList.of(resources), rJarFile.get().asFile.toPath())
    }

    class CreationAction(variantScope: VariantScope) :
        VariantTaskCreationAction<GenerateNamespacedLibraryRFilesTask>(variantScope) {

        override val name: String
            get() = variantScope.getTaskName("create", "RFiles")
        override val type: Class<GenerateNamespacedLibraryRFilesTask>
            get() = GenerateNamespacedLibraryRFilesTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<out GenerateNamespacedLibraryRFilesTask>) {
            super.handleProvider(taskProvider)
            variantScope.artifacts.producesFile(
                InternalArtifactType.COMPILE_ONLY_NAMESPACED_R_CLASS_JAR,
                BuildArtifactsHolder.OperationType.INITIAL,
                taskProvider,
                taskProvider.map { it.rJarFile },
                "R.jar"
            )
        }

        override fun configure(task: GenerateNamespacedLibraryRFilesTask) {
            super.configure(task)

            task.partialRFiles = variantScope.artifacts.getFinalProducts(
                InternalArtifactType.PARTIAL_R_FILES)
            task.packageForRSupplier =
                    Suppliers.memoize(variantScope.variantConfiguration::getOriginalApplicationId)
        }
    }
}
