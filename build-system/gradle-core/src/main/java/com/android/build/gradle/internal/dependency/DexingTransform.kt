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

package com.android.build.gradle.internal.dependency

import com.android.build.gradle.internal.errors.MessageReceiverImpl
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.tasks.recordArtifactTransformSpan
import com.android.build.gradle.options.SyncOptions
import com.android.builder.dexing.ClassFileInputs
import com.android.builder.dexing.DexArchiveBuilder
import com.android.builder.dexing.r8.ClassFileProviderFactory
import com.android.sdklib.AndroidVersion
import com.android.tools.build.gradle.internal.profile.GradleTransformExecutionType
import com.google.common.io.Closer
import com.google.common.io.Files
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.artifacts.ArtifactAttributes.ARTIFACT_FORMAT
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

abstract class BaseDexingTransform : TransformAction<BaseDexingTransform.Parameters> {
    interface Parameters: GenericTransformParameters {
        @get:Input
        val minSdkVersion: Property<Int>
        @get:Input
        val debuggable: Property<Boolean>
        @get:Input
        val enableDesugaring: Property<Boolean>
        @get:Classpath
        val bootClasspath: ConfigurableFileCollection
    }

    @get:Classpath
    @get:InputArtifact
    abstract val primaryInput: File

    protected abstract fun computeClasspathFiles(): List<Path>

    override fun transform(outputs: TransformOutputs) {
        recordArtifactTransformSpan(
            parameters.projectName.get(),
            GradleTransformExecutionType.DEX_ARTIFACT_TRANSFORM
        ) {
            val name = Files.getNameWithoutExtension(primaryInput.name)
            val outputDir = outputs.dir(name)
            Closer.create().use { closer ->

                val d8DexBuilder = DexArchiveBuilder.createD8DexBuilder(
                    parameters.minSdkVersion.get(),
                    parameters.debuggable.get(),
                    ClassFileProviderFactory(parameters.bootClasspath.files.map(File::toPath))
                        .also { closer.register(it) },
                    ClassFileProviderFactory(computeClasspathFiles()).also { closer.register(it) },
                    parameters.enableDesugaring.get(),
                    MessageReceiverImpl(
                        SyncOptions.ErrorFormatMode.MACHINE_PARSABLE,
                        LoggerFactory.getLogger(DexingNoClasspathTransform::class.java)
                    )
                )

                ClassFileInputs.fromPath(primaryInput.toPath()).use { classFileInput ->
                    classFileInput.entries { true }.use { classesInput ->
                        d8DexBuilder.convert(
                            classesInput,
                            outputDir.toPath(),
                            false
                        )
                    }
                }
            }
        }
    }
}

abstract class DexingNoClasspathTransform : BaseDexingTransform() {
    override fun computeClasspathFiles() = listOf<Path>()
}

abstract class DexingWithClasspathTransform : BaseDexingTransform() {
    /**
     * Using compile classpath normalization is safe here due to the design of desugar:
     * Method bodies are only moved to the companion class within the same artifact,
     * not between artifacts.
     */
    @get:CompileClasspath
    @get:InputArtifactDependencies
    abstract val classpath: FileCollection

    override fun computeClasspathFiles() = classpath.files.map(File::toPath)
}

fun getDexingArtifactConfigurations(scopes: Collection<VariantScope>): Set<DexingArtifactConfiguration> {
    return scopes.map { getDexingArtifactConfiguration(it) }.toSet()
}

fun getDexingArtifactConfiguration(scope: VariantScope): DexingArtifactConfiguration {
    val minSdk = scope.variantConfiguration.minSdkVersionWithTargetDeviceApi.featureLevel
    val debuggable = scope.variantConfiguration.buildType.isDebuggable
    val enableDesugaring = scope.java8LangSupportType == VariantScope.Java8LangSupport.D8

    return DexingArtifactConfiguration(minSdk, debuggable, enableDesugaring)
}

data class DexingArtifactConfiguration(
    private val minSdk: Int,
    private val isDebuggable: Boolean,
    private val enableDesugaring: Boolean
) {

    private val needsClasspath = enableDesugaring && minSdk < AndroidVersion.VersionCodes.N

    fun registerTransform(
        projectName: String,
        dependencyHandler: DependencyHandler,
        bootClasspath: FileCollection
    ) {
        dependencyHandler.registerTransform(getTransformClass()) { spec ->
            spec.parameters { parameters ->
                parameters.projectName.set(projectName)
                parameters.minSdkVersion.set(minSdk)
                parameters.debuggable.set(isDebuggable)
                parameters.enableDesugaring.set(enableDesugaring)
                if (needsClasspath) {
                    parameters.bootClasspath.from(bootClasspath)
                }
            }
            spec.from.attribute(ARTIFACT_FORMAT, AndroidArtifacts.ArtifactType.PROCESSED_JAR.type)
            spec.to.attribute(ARTIFACT_FORMAT, AndroidArtifacts.ArtifactType.DEX.type)

            getAttributes().forEach { (attribute, value) ->
                spec.from.attribute(attribute, value)
                spec.to.attribute(attribute, value)
            }
        }
    }

    private fun getTransformClass(): Class<out BaseDexingTransform> {
        return if (needsClasspath) {
            DexingWithClasspathTransform::class.java
        } else {
            DexingNoClasspathTransform::class.java
        }
    }

    fun getAttributes(): Map<Attribute<String>, String> {
        return mapOf(
            ATTR_MIN_SDK to minSdk.toString(),
            ATTR_IS_DEBUGGABLE to isDebuggable.toString(),
            ATTR_ENABLE_DESUGARING to enableDesugaring.toString()
        )
    }
}

val ATTR_MIN_SDK: Attribute<String> = Attribute.of("dexing-min-sdk", String::class.java)
val ATTR_IS_DEBUGGABLE: Attribute<String> =
    Attribute.of("dexing-is-debuggable", String::class.java)
val ATTR_ENABLE_DESUGARING: Attribute<String> =
    Attribute.of("dexing-enable-desugaring", String::class.java)
