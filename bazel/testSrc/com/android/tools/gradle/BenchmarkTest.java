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

package com.android.tools.gradle;

import com.android.SdkConstants;
import com.android.testutils.BazelRunfilesManifestProcessor;
import com.android.testutils.diff.UnifiedDiff;
import com.android.tools.gradle.benchmarkassertions.BenchmarkProjectAssertion;
import com.android.tools.perflogger.Benchmark;
import com.android.tools.perflogger.PerfData;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class BenchmarkTest {

    private static final String ROOT = "prebuilts/studio/";

    public static void main(String[] args) throws Exception {

        File distribution = null;
        File repo = null;
        String project = null;
        String benchmarkName = null;
        String benchmarkBaseName = null;
        String benchmarkCodeType = null;
        String benchmarkFlag = null;
        String benchmarkSize = null;
        String benchmarkType = null;
        boolean benchmarkWithWorkers = false;
        String testProjectGradleRootFromSourceRoot = ".";
        List<String> setupDiffs = new ArrayList<>();
        int warmUps = 0;
        int iterations = 0;
        int removeUpperOutliers = 0;
        int removeLowerOutliers = 0;
        List<String> tasks = new ArrayList<>();
        List<String> startups = new ArrayList<>();
        List<String> cleanups = new ArrayList<>();
        List<File> mutations = new ArrayList<>();
        List<String> mutationDiffs = new ArrayList<>();
        List<BenchmarkProjectAssertion> pre_mutate_assertions = new ArrayList<>();
        List<BenchmarkProjectAssertion> post_mutate_assertions = new ArrayList<>();
        List<String> buildProperties = new ArrayList<>();
        List<BenchmarkListener> listeners = new ArrayList<>();
        boolean fromStudio = false;

        Iterator<String> it = Arrays.asList(args).iterator();
        // See http://cs/android/prebuilts/studio/buildbenchmarks/scenarios.bzl for meaning of
        // these flags.
        while (it.hasNext()) {
            String arg = it.next();
            if (arg.equals("--project") && it.hasNext()) {
                project = it.next();
            } else if (arg.equals("--distribution") && it.hasNext()) {
                distribution = new File(it.next());
            } else if (arg.equals("--repo") && it.hasNext()) {
                repo = new File(it.next());
            } else if (arg.equals("--benchmark_base_name")) {
                benchmarkBaseName = it.next();
            } else if (arg.equals("--benchmark_code_type")) {
                benchmarkCodeType = it.next();
            } else if (arg.equals("--benchmark_flag")) {
                benchmarkFlag = it.next();
            } else if (arg.equals("--benchmark_size")) {
                benchmarkSize = it.next();
            } else if (arg.equals("--benchmark_type")) {
                benchmarkType = it.next();
            } else if (arg.equals("--warmups") && it.hasNext()) {
                warmUps = Integer.valueOf(it.next());
            } else if (arg.equals("--iterations") && it.hasNext()) {
                iterations = Integer.valueOf(it.next());
            } else if (arg.equals("--remove_upper_outliers") && it.hasNext()) {
                removeUpperOutliers = Integer.valueOf(it.next());
            } else if (arg.equals("--remove_lower_outliers") && it.hasNext()) {
                removeLowerOutliers = Integer.valueOf(it.next());
            } else if (arg.equals("--startup_task") && it.hasNext()) {
                startups.add(it.next());
            } else if (arg.equals("--task") && it.hasNext()) {
                tasks.add(it.next());
            } else if (arg.equals("--cleanup_task") && it.hasNext()) {
                cleanups.add(it.next());
            } else if (arg.equals("--benchmark") && it.hasNext()) {
                benchmarkName = it.next();
            } else if (arg.equals("--setup-diff") && it.hasNext()) {
                setupDiffs.add(it.next());
            } else if (arg.equals("--mutation") && it.hasNext()) {
                mutations.add(new File(it.next()));
            } else if (arg.equals("--mutation-diff") && it.hasNext()) {
                mutationDiffs.add(it.next());
            } else if (arg.equals("--build_property") && it.hasNext()) {
                buildProperties.add(it.next());
            } else if (arg.equals("--listener") && it.hasNext()) {
                listeners.add(locateListener(it.next()).newInstance());
            } else if (arg.equals("--from-studio") && it.hasNext()) {
                fromStudio = Boolean.valueOf(it.next());
            } else if (arg.equals("--with-workers") && it.hasNext()) {
                benchmarkWithWorkers = Boolean.valueOf(it.next());
            } else if (arg.equals("--gradle-root") && it.hasNext()) {
                testProjectGradleRootFromSourceRoot = it.next();
            } else if (arg.equals("--pre_mutate_assertion") && it.hasNext()) {
                pre_mutate_assertions.add(instantiateAssertion(it.next()));
            } else if (arg.equals("--post_mutate_assertion") && it.hasNext()) {
                post_mutate_assertions.add(instantiateAssertion(it.next()));
            } else {
                throw new IllegalArgumentException("Unknown flag: " + arg);
            }
        }

        new BenchmarkTest()
                .run(
                        project,
                        benchmarkName,
                        distribution,
                        repo,
                        benchmarkBaseName,
                        benchmarkCodeType,
                        benchmarkFlag,
                        benchmarkSize,
                        benchmarkType,
                        benchmarkWithWorkers,
                        testProjectGradleRootFromSourceRoot,
                        new BenchmarkRun(
                                warmUps, iterations, removeUpperOutliers, removeLowerOutliers),
                        setupDiffs,
                        mutations,
                        mutationDiffs,
                        pre_mutate_assertions,
                        post_mutate_assertions,
                        startups,
                        cleanups,
                        tasks,
                        listeners,
                        buildProperties,
                        fromStudio);
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<? extends T> loadClass(Package relativeBase, String className)
            throws ClassNotFoundException {
        String fqcn =
                className.indexOf('.') != -1 ? className : relativeBase.getName() + "." + className;
        return (Class<? extends T>) BenchmarkTest.class.getClassLoader().loadClass(fqcn);
    }

    private static Class<? extends BenchmarkListener> locateListener(String className)
            throws ClassNotFoundException {
        return loadClass(BenchmarkTest.class.getPackage(), className);
    }

    private static BenchmarkProjectAssertion instantiateAssertion(String argString)
            throws ClassNotFoundException, IllegalAccessException, InvocationTargetException,
                    InstantiationException {
        List<String> args = new ArrayList<>(Splitter.on(';').splitToList(argString));
        Class<? extends BenchmarkProjectAssertion> assertionClass =
                loadClass(BenchmarkProjectAssertion.class.getPackage(), args.remove(0));

        Constructor<?>[] constructors = assertionClass.getConstructors();
        if (constructors.length != 1) {
            throw new RuntimeException(
                    "Expected exactly one constructor in BenchmarkProjectAssertion class "
                            + assertionClass);
        }
        if (constructors[0].getParameterCount() != args.size()) {
            throw new RuntimeException(
                    "Constructor in BenchmarkProjectAssertion class "
                            + assertionClass
                            + " has "
                            + constructors[0].getParameterCount()
                            + " parameters, but "
                            + args.size()
                            + " parameters passed ["
                            + argString
                            + "] (semi-colon separated)");
        }
        return (BenchmarkProjectAssertion) constructors[0].newInstance((Object[]) args.toArray());

    }

    private static String getLocalGradleVersion() throws IOException {
        try (FileInputStream fis = new FileInputStream("tools/buildSrc/base/version.properties")) {
            Properties properties = new Properties();
            properties.load(fis);
            return properties.getProperty("buildVersion");
        }
    }

    public void run(
            String project,
            String benchmarkName,
            File distribution,
            File repo,
            String benchmarkBaseName,
            String benchmarkCodeType,
            String benchmarkFlag,
            String benchmarkSize,
            String benchmarkType,
            boolean benchmarkWithWorkers,
            String testProjectGradleRootFromSourceRoot,
            BenchmarkRun benchmarkRun,
            List<String> setupDiffs,
            List<File> mutations,
            List<String> mutationDiffs,
            List<BenchmarkProjectAssertion> pre_mutate_assertions,
            List<BenchmarkProjectAssertion> post_mutate_assertions,
            List<String> startups,
            List<String> cleanups,
            List<String> tasks,
            List<BenchmarkListener> listeners,
            List<String> buildProperties,
            boolean fromStudio)
            throws Exception {

        BazelRunfilesManifestProcessor.setUpRunfiles();

        Benchmark.Builder benchmarkBuilder =
                new Benchmark.Builder(benchmarkName).setProject("Android Studio Gradle");
        ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.builder();
        mapBuilder.put("benchmarkBaseName", benchmarkBaseName);
        if (benchmarkCodeType != null) {
            mapBuilder.put("benchmarkCodeType", benchmarkCodeType);
        }
        if (benchmarkFlag != null) {
            mapBuilder.put("benchmarkFlag", benchmarkFlag);
        }
        if (benchmarkSize != null) {
            // temporary put both for migrating from one to the other.
            mapBuilder.put("benchmarkCategory", benchmarkSize);
            mapBuilder.put("benchmarkSize", benchmarkSize);
        }
        if (benchmarkType != null) {
            mapBuilder.put("benchmarkType", benchmarkType);
        }
        mapBuilder.put("fromStudio", Boolean.toString(fromStudio));
        mapBuilder.put("benchmarkHost", hostName());
        mapBuilder.put("benchmarkWithWorkers", Boolean.toString(benchmarkWithWorkers));
        benchmarkBuilder.setMetadata(mapBuilder.build());

        Benchmark benchmark = benchmarkBuilder.build();

        File data = new File(ROOT + "buildbenchmarks/" + project);
        File out = new File(System.getenv("TEST_TMPDIR"), "tmp_gradle_out");
        File src = new File(System.getenv("TEST_TMPDIR"), "tmp_gradle_src");
        File home = new File(System.getenv("TEST_TMPDIR"), "tmp_home");
        home.mkdirs();

        Gradle.unzip(new File(data, "src.zip"), src);
        for (String setupDiff : setupDiffs) {
            UnifiedDiff diff = new UnifiedDiff(new File(data, setupDiff));
            diff.apply(src, 3);
        }

        mutations.addAll(
                mutationDiffs.stream().map(s -> new File(data, s)).collect(Collectors.toList()));
        UnifiedDiff[] diffs = new UnifiedDiff[mutations.size()];
        for (int i = 0; i < mutations.size(); i++) {
            diffs[i] = new UnifiedDiff(mutations.get(i));
        }

        File projectRoot = new File(src, testProjectGradleRootFromSourceRoot);
        try (Gradle gradle = new Gradle(projectRoot, out, distribution)) {
            gradle.addRepo(repo);
            gradle.addRepo(new File(data, "repo.zip"));
            gradle.addArgument("-Dcom.android.gradle.version=" + getLocalGradleVersion());
            gradle.addArgument("-Duser.home=" + home.getAbsolutePath());
            if (fromStudio) {
                gradle.addArgument("-Pandroid.injected.invoked.from.ide=true");
                gradle.addArgument("-Pandroid.injected.testOnly=true");
                gradle.addArgument(
                        "-Pandroid.injected.build.api=10000"); // as high as possible so we never need to change it.
                gradle.addArgument("-Pandroid.injected.build.abi=arm64-v8a");
                gradle.addArgument("-Pandroid.injected.build.density=xhdpi");
            }
            buildProperties.forEach(gradle::addArgument);

            listeners.forEach(it -> it.configure(home, gradle, benchmarkRun));

            gradle.run(startups);

            listeners.forEach(it -> it.benchmarkStarting(benchmark));

            for (int i = 0; i < benchmarkRun.warmUps + benchmarkRun.iterations; i++) {
                if (!cleanups.isEmpty()) {
                    gradle.run(cleanups);
                }

                for (int j = 0; j < diffs.length; j++) {
                    diffs[j].apply(src, 3);
                    diffs[j] = diffs[j].invert();
                }
                if (i >= benchmarkRun.warmUps) {
                    listeners.forEach(it -> it.iterationStarting());
                }
                gradle.run(tasks);
                if (i >= benchmarkRun.warmUps) {
                    listeners.forEach(BenchmarkListener::iterationDone);
                }
                try {
                    checkResult(
                            projectRoot,
                            i % 2 == 0, // Even benchmarks have the diff and odd ones do not.
                            pre_mutate_assertions,
                            post_mutate_assertions);
                } catch (AssertionError e) {
                    throw new AssertionError(
                            String.format(
                                    "Benchmark %s$1 assertion failed at iteration %d$2",
                                    benchmarkName, i),
                            e);
                }

            }

            listeners.forEach(BenchmarkListener::benchmarkDone);

            PerfData perfData = new PerfData();
            perfData.addBenchmark(benchmark);
            perfData.commit();
        }
    }

    private static String hostName() {
        if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_LINUX) {
            return "Linux";
        } else if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_DARWIN) {
            return "Mac";
        } else if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_WINDOWS) {
            return "Windows";
        }

        throw new RuntimeException("Unexpected platform.");
    }

    private void checkResult(
            File projectRoot,
            boolean expectMutated,
            List<BenchmarkProjectAssertion> pre_mutate_assertions,
            List<BenchmarkProjectAssertion> post_mutate_assertions)
            throws Exception {
        List<BenchmarkProjectAssertion> assertions;
        if (expectMutated) {

            assertions = post_mutate_assertions;
        } else {
            assertions = pre_mutate_assertions;
        }
        for (BenchmarkProjectAssertion assertion : assertions) {
            assertion.checkProject(projectRoot.toPath());
        }
    }
}
