/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.build.gradle.internal.cxx.cmake.MakeCmakeMessagePathsAbsoluteKt.makeCmakeMessagePathsAbsolute;
import static com.android.build.gradle.internal.cxx.configure.CmakeAndroidGradleBuildExtensionsKt.wrapCmakeListsForCompilerSettingsCaching;
import static com.android.build.gradle.internal.cxx.configure.CmakeAndroidGradleBuildExtensionsKt.writeCompilerSettingsToCache;
import static com.android.build.gradle.internal.cxx.logging.LoggingEnvironmentKt.errorln;
import static com.google.common.base.Preconditions.checkState;

import com.android.annotations.NonNull;
import com.android.build.gradle.internal.core.Abi;
import com.android.build.gradle.internal.cxx.model.CxxAbiModel;
import com.android.build.gradle.internal.cxx.model.CxxCmakeModuleModel;
import com.android.build.gradle.internal.cxx.model.CxxVariantModel;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessInfoBuilder;
import com.android.utils.FileUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.wireless.android.sdk.stats.GradleBuildVariant;
import com.google.wireless.android.sdk.stats.GradleNativeAndroidModule;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CMake JSON generation logic. This is separated from the corresponding CMake task so that JSON can
 * be generated during configuration.
 */
abstract class CmakeExternalNativeJsonGenerator extends ExternalNativeJsonGenerator {
    @NonNull protected final CxxCmakeModuleModel cmake;

    CmakeExternalNativeJsonGenerator(
            @NonNull CxxVariantModel variant,
            @NonNull List<CxxAbiModel> abis,
            @NonNull GradleBuildVariant.Builder stats) {
        super(variant, abis, stats);
        this.stats.setNativeBuildSystemType(GradleNativeAndroidModule.NativeBuildSystemType.CMAKE);
        this.cmake = Objects.requireNonNull(variant.getModule().getCmake());

        // Check some basic requirements. This code executes at sync time but any call to
        // recordConfigurationError will later cause the generation of json to fail.
        File cmakelists = getMakefile();
        if (cmakelists.isDirectory()) {
            errorln(
                    "Gradle project cmake.path %s is a folder. It must be CMakeLists.txt",
                    cmakelists);
        } else if (cmakelists.isFile()) {
            String filename = cmakelists.getName();
            if (!filename.equals("CMakeLists.txt")) {
                errorln(
                        "Gradle project cmake.path specifies %s but it must be CMakeLists.txt",
                        filename);
            }
        } else {
            errorln("Gradle project cmake.path is %s but that file doesn't exist", cmakelists);
        }
    }

    /**
     * Returns the cache arguments for implemented strategy.
     *
     * @return Returns the cache arguments
     */
    @NonNull
    abstract List<String> getCacheArguments(@NonNull CxxAbiModel abi);

    /**
     * Executes the JSON generation process. Return the combination of STDIO and STDERR from running
     * the process.
     *
     * @return Returns the combination of STDIO and STDERR from running the process.
     */
    @NonNull
    public abstract String executeProcessAndGetOutput(@NonNull CxxAbiModel abi)
            throws ProcessException, IOException;

    @NonNull
    @Override
    public String executeProcess(@NonNull CxxAbiModel abi) throws ProcessException, IOException {
        String output = executeProcessAndGetOutput(abi);
        return makeCmakeMessagePathsAbsolute(output, getMakefile().getParentFile());
    }

    @Override
    void processBuildOutput(@NonNull String buildOutput, @NonNull CxxAbiModel abi) {
        if (variant.getModule().isNativeCompilerSettingsCacheEnabled()) {
            writeCompilerSettingsToCache(abi);
        }
    }

    @NonNull
    @Override
    ProcessInfoBuilder getProcessBuilder(@NonNull CxxAbiModel abi) {
        ProcessInfoBuilder builder = new ProcessInfoBuilder();

        builder.setExecutable(cmake.getCmakeExe());
        builder.addArgs(getProcessBuilderArgs(abi));

        return builder;
    }

    /** Returns the list of arguments to be passed to process builder. */
    @VisibleForTesting
    @NonNull
    List<String> getProcessBuilderArgs(@NonNull CxxAbiModel abi) {
        List<String> processBuilderArgs = Lists.newArrayList();
        // CMake requires a folder. Trim the filename off.
        File cmakeListsFolder = getMakefile().getParentFile();
        processBuilderArgs.add(String.format("-H%s", cmakeListsFolder));
        processBuilderArgs.add(String.format("-B%s", abi.getCxxBuildFolder()));
        processBuilderArgs.addAll(getCacheArguments(abi));

        // Add user provided build arguments
        processBuilderArgs.addAll(getBuildArguments());
        if (variant.getModule().isNativeCompilerSettingsCacheEnabled()) {
            return wrapCmakeListsForCompilerSettingsCaching(abi, processBuilderArgs).getArgs();
        }
        return processBuilderArgs;
    }

    /**
     * Returns a list of default cache arguments that the implementations may use.
     *
     * @return list of default cache arguments
     */
    protected List<String> getCommonCacheArguments(@NonNull CxxAbiModel abi) {
        List<String> cacheArguments = Lists.newArrayList();
        cacheArguments.add(String.format("-DANDROID_ABI=%s", abi.getAbi().getTag()));
        cacheArguments.add(
                String.format("-DANDROID_PLATFORM=android-%s", abi.getAbiPlatformVersion()));
        cacheArguments.add(
                String.format(
                        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=%s",
                        new File(getObjFolder(), abi.getAbi().getTag())));
        cacheArguments.add(
                String.format("-DCMAKE_BUILD_TYPE=%s", isDebuggable() ? "Debug" : "Release"));
        cacheArguments.add(String.format("-DANDROID_NDK=%s", getNdkFolder()));
        if (!getcFlags().isEmpty()) {
            cacheArguments.add(
                    String.format("-DCMAKE_C_FLAGS=%s", Joiner.on(" ").join(getcFlags())));
        }

        if (!getCppFlags().isEmpty()) {
            cacheArguments.add(
                    String.format("-DCMAKE_CXX_FLAGS=%s", Joiner.on(" ").join(getCppFlags())));
        }

        return cacheArguments;
    }

    @NonNull
    @Override
    public NativeBuildSystem getNativeBuildSystem() {
        return NativeBuildSystem.CMAKE;
    }

    @NonNull
    @Override
    Map<Abi, File> getStlSharedObjectFiles() {
        // Search for ANDROID_STL build argument. Process in order / later flags take precedent.
        String stl = null;
        File ndkBasePath = null;
        for (String argument : getBuildArguments()) {
            argument = argument.replace(" ", "");
            switch (argument) {
                case "-DANDROID_STL=stlport_shared":
                    stl = "stlport";
                    ndkBasePath = FileUtils.join(getNdkFolder(), "sources", "cxx-stl", "stlport");
                    break;
                case "-DANDROID_STL=gnustl_shared":
                    stl = "gnustl";
                    ndkBasePath =
                            FileUtils.join(
                                    getNdkFolder(), "sources", "cxx-stl", "gnu-libstdc++", "4.9");
                    break;
                case "-DANDROID_STL=c++_shared":
                    stl = "c++";
                    ndkBasePath =
                            FileUtils.join(getNdkFolder(), "sources", "cxx-stl", "llvm-libc++");
                    break;
            }
        }
        Map<Abi, File> result = Maps.newHashMap();
        if (stl == null) {
            return result;
        }
        for (Abi abi : getAbis()) {
            File file =
                    FileUtils.join(
                            ndkBasePath,
                            "libs",
                            abi.getTag(),
                            String.format("lib%s_shared.so", stl));
            checkState(file.isFile(), "Expected NDK STL shared object file at %s", file.toString());
            result.put(abi, file);
        }
        return result;
    }
}
