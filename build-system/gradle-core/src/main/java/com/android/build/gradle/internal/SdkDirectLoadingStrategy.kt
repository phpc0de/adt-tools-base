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

package com.android.build.gradle.internal

import com.android.SdkConstants
import com.android.builder.core.ToolsRevisionUtils
import com.android.builder.errors.EvalIssueReporter
import com.android.builder.internal.compiler.RenderScriptProcessor
import com.android.builder.model.Version
import com.android.repository.Revision
import com.android.sdklib.AndroidTargetHash
import com.android.sdklib.AndroidVersion
import com.android.sdklib.BuildToolInfo
import com.android.sdklib.OptionalLibrary
import com.android.sdklib.repository.meta.DetailsTypes
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * This loads the SDK and BuildTools specified inside the {@code options} directly, without
 * performing a full SDK scan (see {@link SdkFullLoadingStrategy}).
 *
 * <p>This is done by trying to predict the directory of the buildtool/SDK requested and parsing
 * only those package.xml files (with some basic validation).
 *
 * <p>Unlike the full SDK scan, it cannot load/find sdk components that are installed in a
 * non-default directory structure.
 */
class SdkDirectLoadingStrategy(
    private val sdkLocationSourceSet: SdkLocationSourceSet,
    private val platformTargetHashSupplier: Supplier<String?>,
    private val buildToolRevisionSupplier: Supplier<Revision?>,
    private val useAndroidX: Boolean,
    private val evalIssueReporter: EvalIssueReporter) {

    companion object {
        // We use Optional<> wrapper since ConcurrentHashMap don't support null values.
        private val buildToolsCache = ConcurrentHashMap<Revision, Optional<BuildToolInfo>>()
        private val platformCache = ConcurrentHashMap<String, Optional<PlatformComponents>>()

        @VisibleForTesting
        @Synchronized
        fun clearCaches() {
            buildToolsCache.clear()
            platformCache.clear()
        }
    }

    private val components: DirectLoadComponents? by lazy {
        init()
    }

    /** Holder of all components loaded **/
    private class DirectLoadComponents(
        internal val platformTools: PlatformToolsComponents,
        internal val supportTools: SupportToolsComponents,
        internal val buildToolInfo: BuildToolInfo,
        internal val platform: PlatformComponents)

    @Synchronized
    private fun init(): DirectLoadComponents? {
        val targetHash = checkNotNull(platformTargetHashSupplier.get()) {
            "Extension not initialized yet, couldn't access compileSdkVersion."}
        val buildToolRevision = checkBuildToolsRevision(
            checkNotNull(buildToolRevisionSupplier.get()) {
                "Extension not initialized yet, couldn't access buildToolsVersion."})

        return loadSdkComponents(targetHash, buildToolRevision)
    }

    private fun loadSdkComponents(targetHash: String, buildToolRevision: Revision): DirectLoadComponents? {
        val sdkLocation = SdkLocator.getSdkLocation(sdkLocationSourceSet)
        if (sdkLocation.type == SdkType.MISSING) {
            return null
        }

        val sdkDirectory = sdkLocation.directory!!

        val platformTools = PlatformToolsComponents.build(sdkDirectory)
        val supportTools = SupportToolsComponents.build(sdkDirectory)

        val buildTools = buildToolsCache.getOrPut(buildToolRevision) {
            Optional.ofNullable(buildBuildTools(sdkDirectory, buildToolRevision))
        }.orElse(null)
        val platform = platformCache.getOrPut(targetHash) {
            Optional.ofNullable(PlatformComponents.build(sdkDirectory, targetHash))
        }.orElse(null)

        if (platformTools == null || supportTools == null || buildTools == null || platform == null) {
            return null
        }

        return DirectLoadComponents(platformTools, supportTools, buildTools, platform)
    }

    private fun checkBuildToolsRevision(revision: Revision): Revision {
        if (revision < ToolsRevisionUtils.MIN_BUILD_TOOLS_REV) {
            evalIssueReporter
                .reportWarning(
                    EvalIssueReporter.Type.BUILD_TOOLS_TOO_LOW,
                    String.format(
                        "The specified Android SDK Build Tools version (%1\$s) is "
                                + "ignored, as it is below the minimum supported "
                                + "version (%2\$s) for Android Gradle Plugin %3\$s.\n"
                                + "Android SDK Build Tools %4\$s will be used.\n"
                                + "To suppress this warning, "
                                + "remove \"buildToolsVersion '%1\$s'\" "
                                + "from your build.gradle file, as each "
                                + "version of the Android Gradle Plugin now has a "
                                + "default version of the build tools.",
                        revision,
                        ToolsRevisionUtils.MIN_BUILD_TOOLS_REV,
                        Version.ANDROID_GRADLE_PLUGIN_VERSION,
                        ToolsRevisionUtils.DEFAULT_BUILD_TOOLS_REVISION),
                    ToolsRevisionUtils.DEFAULT_BUILD_TOOLS_REVISION.toString())

            return ToolsRevisionUtils.DEFAULT_BUILD_TOOLS_REVISION
        }
        return revision
    }

    fun loadedSuccessfully() = components != null

    // From "SdkInfo".
    fun getAdbExecutable() = components?.platformTools?.adbExecutable
    fun getAnnotationsJar() = components?.supportTools?.annotationsJar

    // From IAndroidTarget
    fun getAidlFramework() = components?.platform?.aidlFramework
    fun getAndroidJar() = components?.platform?.androidJar
    fun getAdditionalLibraries() = components?.platform?.additionalLibraries
    fun getOptionalLibraries() = components?.platform?.optionalLibraries
    fun getTargetPlatformVersion() = components?.platform?.targetPlatformVersion
    fun getTargetBootClasspath() = components?.platform?.targetBootClasspath

    // From BuildToolInfo
    fun getBuildToolsInfo() = components?.buildToolInfo
    fun getBuildToolsRevision() = getBuildToolsInfo()?.revision

    private fun getFileFromBuildTool(component: BuildToolInfo.PathId) =
        getBuildToolsInfo()?.let { File(it.getPath(component)) }

    fun getAidlExecutable() = getFileFromBuildTool(BuildToolInfo.PathId.AIDL)
    fun getCoreLambaStubs() = getFileFromBuildTool(BuildToolInfo.PathId.CORE_LAMBDA_STUBS)
    fun getSplitSelectExecutable() = getFileFromBuildTool(BuildToolInfo.PathId.SPLIT_SELECT)

    fun getRenderScriptSupportJar() = getBuildToolsInfo()?.let {
        RenderScriptProcessor.getSupportJar(it.location, useAndroidX)
    }

    fun getSupportNativeLibFolder() = getBuildToolsInfo()?.let {
        RenderScriptProcessor.getSupportNativeLibFolder(it.location)
    }

    fun getSupportBlasLibFolder() = getBuildToolsInfo()?.let {
        RenderScriptProcessor.getSupportBlasLibFolder(it.location)
    }

    fun reset() {
        clearCaches()
    }
}

private class PlatformToolsComponents(
    /** This is the ADB executable, usually $SDK/platform-tools/adb **/
    internal val adbExecutable: File) {

    companion object {
        internal fun build(sdkDirectory: File): PlatformToolsComponents? {
            val platformToolsPackageXml = sdkDirectory.resolve(SdkConstants.FD_PLATFORM_TOOLS).resolve("package.xml")
            if (!platformToolsPackageXml.exists()) {
                return null
            }
            return PlatformToolsComponents(
                sdkDirectory.resolve(SdkConstants.FD_PLATFORM_TOOLS).resolve(SdkConstants.FN_ADB))
        }
    }
}

private class SupportToolsComponents(
    /** This is the Annotations.jar, usually $SDK/tools/support/annotations.jar **/
    internal val annotationsJar: File) {

    companion object {
        internal fun build(sdkDirectory: File): SupportToolsComponents? {
            val supportToolsPackageXml = sdkDirectory.resolve(SdkConstants.FD_TOOLS).resolve("package.xml")
            if (!supportToolsPackageXml.exists()) {
                return null
            }
            return SupportToolsComponents(
                sdkDirectory.resolve(SdkConstants.FD_TOOLS).resolve(SdkConstants.FD_SUPPORT)
                    .resolve(SdkConstants.FN_ANNOTATIONS_JAR))
        }
    }
}

/**
 * This holds all components related to the platform targeted, usually
 * $SDK/platforms/android-{api_level}/
 */
private class PlatformComponents(
    /** This is the platform version **/
    internal val targetPlatformVersion: AndroidVersion,
    /** This is the framework.aidl, usually $PLATFORM/framework.aidl **/
    internal val aidlFramework: File,
    /** This is the android.jar, usually $PLATFORM/android.jar **/
    internal val androidJar: File,
    /** This is the boot classpath of this platform, usually it's composed of only the android.jar **/
    internal val targetBootClasspath: List<File>,
    /** This is the additional libraries of this platform. **/
    internal val additionalLibraries: List<OptionalLibrary>,
    /** This is the optional libraries of this platform. **/
    internal val optionalLibraries: List<OptionalLibrary>) {

    companion object {
        internal fun build(sdkDirectory: File, targetHash: String): PlatformComponents? {
            val platformVersion = AndroidTargetHash.getVersionFromHash(targetHash)
                ?: return null // We are not sure which version this hash maps to.

            val platformId = DetailsTypes.getPlatformPath(platformVersion)
            val platformBase = sdkDirectory.resolve(platformId.replace(';', '/'))
            val platformXml = platformBase.resolve("package.xml")
            val platformPackage = parsePackage(platformXml)
            if (platformPackage == null || !platformId.equals(platformPackage.path)) {
                return null
            }
            // Building a whole PlatformTarget is expensive, since it tries to parse everything.
            // So we build manually the fields we need.

            return PlatformComponents(
                platformVersion,
                platformBase.resolve(SdkConstants.FN_FRAMEWORK_AIDL),
                platformBase.resolve(SdkConstants.FN_FRAMEWORK_LIBRARY),
                // See PlatformTarget.java, getBootClasspath is always a list with only the android.jar.
                ImmutableList.of(platformBase.resolve(SdkConstants.FN_FRAMEWORK_LIBRARY)),
                parseAdditionalLibraries(platformPackage),
                parseOptionalLibraries(platformPackage))
        }
    }
}