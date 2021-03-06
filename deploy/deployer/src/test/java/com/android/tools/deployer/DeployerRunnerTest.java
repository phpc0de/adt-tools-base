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
package com.android.tools.deployer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.testutils.AssumeUtil;
import com.android.testutils.TestUtils;
import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.protobuf.ByteString;
import com.android.tools.deploy.protobuf.CodedInputStream;
import com.android.tools.deploy.protobuf.CodedOutputStream;
import com.android.tools.deployer.devices.FakeDevice;
import com.android.tools.deployer.devices.FakeDeviceLibrary;
import com.android.tools.deployer.devices.FakeDeviceLibrary.DeviceId;
import com.android.tools.deployer.devices.shell.Arguments;
import com.android.tools.deployer.devices.shell.ShellCommand;
import com.android.tools.deployer.devices.shell.interpreter.ShellContext;
import com.android.utils.FileUtils;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@RunWith(Parameterized.class)
public class DeployerRunnerTest extends FakeAdbTestBase {

    private UIService service;

    @Parameterized.Parameters(name = "{0}")
    public static DeviceId[] getDevices() {
        return DeviceId.values();
    }

    public DeployerRunnerTest(DeviceId id) {
        super(new FakeDeviceLibrary().build(id));
    }

    @Before
    public void setUp() {
        this.service = Mockito.mock(UIService.class);
    }

    @After
    public void tearDown() {
        Mockito.verifyNoMoreInteractions(service);
    }

    @Test
    public void testFullInstallSuccessful() throws Exception {
        assertTrue(device.getApps().isEmpty());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File file = TestUtils.getWorkspaceFile(BASE + "sample.apk");
        String[] args = {
            "install", "com.example.helloworld", file.getAbsolutePath(), "--force-full-install"
        };
        int retcode = runner.run(args, logger);
        assertEquals(0, retcode);
        assertEquals(1, device.getApps().size());
        assertInstalled("com.example.helloworld", file);
        assertMetrics(
                runner.getMetrics(),
                "DELTAINSTALL:DISABLED",
                "INSTALL:OK",
                "DDMLIB_UPLOAD",
                "DDMLIB_INSTALL");
        assertFalse(device.hasFile("/data/local/tmp/sample.apk"));
    }

    @Test
    public void testAttemptDeltaInstallWithoutPreviousInstallation() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File file = TestUtils.getWorkspaceFile(BASE + "sample.apk");
        File installersPath = prepareInstaller();
        String[] args = {
            "install",
            "com.example.helloworld",
            file.getAbsolutePath(),
            "--installers-path=" + installersPath.getAbsolutePath()
        };
        int retcode = runner.run(args, logger);
        assertEquals(0, retcode);
        assertEquals(1, device.getApps().size());

        assertInstalled("com.example.helloworld", file);

        if (device.getApi() < 21) {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
            assertHistory(
                    device,
                    "getprop",
                    "pm install -r -t \"/data/local/tmp/sample.apk\"",
                    "rm \"/data/local/tmp/sample.apk\"");
        } else if (device.getApi() < 24) {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
            assertHistory(
                    device,
                    "getprop",
                    "pm install-create -r -t -S ${size:com.example.helloworld}",
                    "pm install-write -S ${size:com.example.helloworld} 1 0_sample -",
                    "pm install-commit 1");
        } else {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DUMP_UNKNOWN_PACKAGE",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
            assertHistory(
                    device,
                    "getprop",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.helloworld",
                    "mkdir -p /data/local/tmp/.studio/bin",
                    "chmod +x /data/local/tmp/.studio/bin/installer",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.helloworld",
                    "/system/bin/run-as com.example.helloworld id -u",
                    "/system/bin/cmd package path com.example.helloworld",
                    "/system/bin/pm path com.example.helloworld", // TODO: we should not always attempt both paths
                    "cmd package install-create -r -t -S ${size:com.example.helloworld}",
                    "cmd package install-write -S ${size:com.example.helloworld} 1 0_sample -",
                    "cmd package install-commit 1");
        }
    }

    @Test
    public void testSkipInstall() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File file = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install", "com.example.simpleapp", file.getAbsolutePath(), "--force-full-install"
        };

        assertEquals(0, runner.run(args, logger));
        assertInstalled("com.example.simpleapp", file);
        assertMetrics(
                runner.getMetrics(),
                "DELTAINSTALL:DISABLED",
                "INSTALL:OK",
                "DDMLIB_UPLOAD",
                "DDMLIB_INSTALL");
        device.getShell().clearHistory();

        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    file.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };
        int retcode = runner.run(args, logger);
        assertEquals(0, retcode);
        assertEquals(1, device.getApps().size());

        assertInstalled("com.example.simpleapp", file);

        if (device.getApi() < 24) {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        } else {
            assertHistory(
                    device,
                    "getprop",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "mkdir -p /data/local/tmp/.studio/bin",
                    "chmod +x /data/local/tmp/.studio/bin/installer",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "/system/bin/run-as com.example.simpleapp id -u",
                    "id -u",
                    "/system/bin/cmd package path com.example.simpleapp",
                    "am force-stop com.example.simpleapp");
            assertMetrics(runner.getMetrics(), "INSTALL:SKIPPED_INSTALL");
        }
    }

    @Test
    public void testDeltaInstall() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File file = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install", "com.example.simpleapp", file.getAbsolutePath(), "--force-full-install"
        };

        assertEquals(0, runner.run(args, logger));
        assertInstalled("com.example.simpleapp", file);
        assertMetrics(
                runner.getMetrics(),
                "DELTAINSTALL:DISABLED",
                "INSTALL:OK",
                "DDMLIB_UPLOAD",
                "DDMLIB_INSTALL");
        device.getShell().clearHistory();

        file = TestUtils.getWorkspaceFile(BASE + "apks/simple+code.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    file.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };
        int retcode = runner.run(args, logger);
        assertEquals(0, retcode);
        assertEquals(1, device.getApps().size());

        assertInstalled("com.example.simpleapp", file);

        if (device.getApi() < 24) {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        } else {
            assertHistory(
                    device,
                    "getprop",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "mkdir -p /data/local/tmp/.studio/bin",
                    "chmod +x /data/local/tmp/.studio/bin/installer",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "/system/bin/run-as com.example.simpleapp id -u",
                    "id -u",
                    "/system/bin/cmd package path com.example.simpleapp",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION deltainstall",
                    "/system/bin/cmd package install-create -t -r",
                    "cmd package install-write -S ${size:com.example.simpleapp} 2 base.apk",
                    "/system/bin/cmd package install-commit 2");
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL_UPLOAD",
                    "DELTAINSTALL_INSTALL",
                    "DELTAINSTALL:SUCCESS");
        }
    }

    @Test
    public void testInstallOldVersion() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File v2 = TestUtils.getWorkspaceFile(BASE + "apks/simple+ver.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install", "com.example.simpleapp", v2.getAbsolutePath(), "--force-full-install"
        };

        assertEquals(0, runner.run(args, logger));
        assertInstalled("com.example.simpleapp", v2);
        assertMetrics(
                runner.getMetrics(),
                "DELTAINSTALL:DISABLED",
                "INSTALL:OK",
                "DDMLIB_UPLOAD",
                "DDMLIB_INSTALL");

        device.getShell().clearHistory();

        File v1 = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    v1.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };

        Mockito.when(service.prompt(ArgumentMatchers.anyString())).thenReturn(false);

        int retcode = runner.run(args, logger);
        assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), retcode);
        assertEquals(1, device.getApps().size());

        // Check old app still installed
        assertInstalled("com.example.simpleapp", v2);

        if (device.getApi() == 19) {
            assertHistory(
                    device, "getprop", "pm install -r -t \"/data/local/tmp/simple.apk\""
                    // ,"rm \"/data/local/tmp/simple.apk\"" TODO: ddmlib doesn't remove when installation fails
                    );
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:INSTALL_FAILED_VERSION_DOWNGRADE");
        } else if (device.getApi() < 24) {
            assertHistory(
                    device,
                    "getprop",
                    "pm install-create -r -t -S ${size:com.example.simpleapp}", // TODO: passing size on create?
                    "pm install-write -S ${size:com.example.simpleapp} 2 0_simple -",
                    "pm install-commit 2");
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:INSTALL_FAILED_VERSION_DOWNGRADE");
        } else {
            assertHistory(
                    device,
                    "getprop",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "mkdir -p /data/local/tmp/.studio/bin",
                    "chmod +x /data/local/tmp/.studio/bin/installer",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "/system/bin/run-as com.example.simpleapp id -u",
                    "id -u",
                    "/system/bin/cmd package path com.example.simpleapp",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION deltainstall",
                    "/system/bin/cmd package install-create -t -r",
                    "cmd package install-write -S ${size:com.example.simpleapp} 2 base.apk",
                    "/system/bin/cmd package install-commit 2");
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL_UPLOAD",
                    "DELTAINSTALL_INSTALL",
                    "DELTAINSTALL:ERROR.INSTALL_FAILED_VERSION_DOWNGRADE");
        }
        Mockito.verify(service, Mockito.times(1)).prompt(ArgumentMatchers.anyString());
    }

    @Test
    public void testInstallSplit() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split = TestUtils.getWorkspaceFile(BASE + "apks/split.apk");

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertInstalled("com.example.simpleapp", base, split);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        }
    }

    @Test
    public void testInstallVersionMismatchSplit() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split = TestUtils.getWorkspaceFile(BASE + "apks/split+ver.apk");

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
        if (device.getApi() < 21) {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:INSTALL_FAILED_INVALID_APK");
        }
    }

    @Test
    public void testBadDeltaOnSplit() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split = TestUtils.getWorkspaceFile(BASE + "apks/split.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertInstalled("com.example.simpleapp", base, split);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        }

        device.getShell().clearHistory();

        File update = TestUtils.getWorkspaceFile(BASE + "apks/split+ver.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    base.getAbsolutePath(),
                    update.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };

        code = runner.run(args, logger);

        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertEquals(1, device.getApps().size());

            // Check old app still installed
            assertInstalled("com.example.simpleapp", base, split);

            if (device.getApi() < 24) {
                assertHistory(
                        device,
                        "getprop",
                        "pm install-create -r -t -S ${size:com.example.simpleapp}",
                        "pm install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple -",
                        "pm install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split_ver -",
                        "pm install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:API_NOT_SUPPORTED",
                        "INSTALL:INSTALL_FAILED_INVALID_APK");
            } else {
                assertHistory(
                        device,
                        "getprop",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "mkdir -p /data/local/tmp/.studio/bin",
                        "chmod +x /data/local/tmp/.studio/bin/installer",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "/system/bin/run-as com.example.simpleapp id -u",
                        "id -u",
                        "/system/bin/cmd package path com.example.simpleapp",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION deltainstall",
                        "/system/bin/cmd package install-create -t -r",
                        "cmd package install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 split_split_01.apk",
                        "cmd package install-write -S ${size:com.example.simpleapp:base.apk} 2 base.apk",
                        "/system/bin/cmd package install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL_UPLOAD",
                        "DELTAINSTALL_INSTALL",
                        "DELTAINSTALL:ERROR.INSTALL_FAILED_INVALID_APK");
            }
        }
    }

    @Test
    public void testDeltaOnSplit() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split = TestUtils.getWorkspaceFile(BASE + "apks/split.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertInstalled("com.example.simpleapp", base, split);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        }

        device.getShell().clearHistory();

        File update = TestUtils.getWorkspaceFile(BASE + "apks/split+code.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    base.getAbsolutePath(),
                    update.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };

        code = runner.run(args, logger);

        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertEquals(0, code);
            assertEquals(1, device.getApps().size());

            // Check new app installed
            assertInstalled("com.example.simpleapp", base, update);

            if (device.getApi() < 24) {
                assertHistory(
                        device,
                        "getprop",
                        "pm install-create -r -t -S ${size:com.example.simpleapp}",
                        "pm install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple -",
                        "pm install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split_code -",
                        "pm install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:API_NOT_SUPPORTED",
                        "INSTALL:OK",
                        "DDMLIB_UPLOAD",
                        "DDMLIB_INSTALL");
            } else {
                assertHistory(
                        device,
                        "getprop",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "mkdir -p /data/local/tmp/.studio/bin",
                        "chmod +x /data/local/tmp/.studio/bin/installer",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "/system/bin/run-as com.example.simpleapp id -u",
                        "id -u",
                        "/system/bin/cmd package path com.example.simpleapp",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION deltainstall",
                        "/system/bin/cmd package install-create -t -r -p com.example.simpleapp",
                        "cmd package install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 split_split_01.apk",
                        "/system/bin/cmd package install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL_UPLOAD",
                        "DELTAINSTALL_INSTALL",
                        "DELTAINSTALL:SUCCESS");
            }
        }
    }

    @Test
    public void testAddSplit() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split = TestUtils.getWorkspaceFile(BASE + "apks/split.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertInstalled("com.example.simpleapp", base, split);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        }

        device.getShell().clearHistory();

        File added = TestUtils.getWorkspaceFile(BASE + "apks/split2.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    base.getAbsolutePath(),
                    split.getAbsolutePath(),
                    added.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };

        code = runner.run(args, logger);

        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertEquals(0, code);
            assertEquals(1, device.getApps().size());

            // Check new app installed
            assertInstalled("com.example.simpleapp", base, split, added);

            if (device.getApi() < 24) {
                assertHistory(
                        device,
                        "getprop",
                        "pm install-create -r -t -S ${size:com.example.simpleapp}",
                        "pm install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple -",
                        "pm install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split -",
                        "pm install-write -S ${size:com.example.simpleapp:split_split_02.apk} 2 2_split_ -",
                        "pm install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:API_NOT_SUPPORTED",
                        "INSTALL:OK",
                        "DDMLIB_UPLOAD",
                        "DDMLIB_INSTALL");
            } else {
                assertHistory(
                        device,
                        "getprop",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "mkdir -p /data/local/tmp/.studio/bin",
                        "chmod +x /data/local/tmp/.studio/bin/installer",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "/system/bin/run-as com.example.simpleapp id -u",
                        "id -u",
                        "/system/bin/cmd package path com.example.simpleapp",
                        "cmd package install-create -r -t -S ${size:com.example.simpleapp}",
                        "cmd package install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple -",
                        "cmd package install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split -",
                        "cmd package install-write -S ${size:com.example.simpleapp:split_split_02.apk} 2 2_split_ -",
                        "cmd package install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:CANNOT_GENERATE_DELTA",
                        "INSTALL:OK",
                        "DDMLIB_UPLOAD",
                        "DDMLIB_INSTALL");
            }
        }
    }

    @Test
    public void testRemoveSplit() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split1 = TestUtils.getWorkspaceFile(BASE + "apks/split.apk");
        File split2 = TestUtils.getWorkspaceFile(BASE + "apks/split2.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split1.getAbsolutePath(),
            split2.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertInstalled("com.example.simpleapp", base, split1, split2);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        }

        device.getShell().clearHistory();

        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    base.getAbsolutePath(),
                    split1.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };

        code = runner.run(args, logger);

        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertEquals(0, code);
            assertEquals(1, device.getApps().size());

            // Check new app installed
            assertInstalled("com.example.simpleapp", base, split1);

            if (device.getApi() < 24) {
                assertHistory(
                        device,
                        "getprop",
                        "pm install-create -r -t -S ${size:com.example.simpleapp}",
                        "pm install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple -",
                        "pm install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split -",
                        "pm install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:API_NOT_SUPPORTED",
                        "INSTALL:OK",
                        "DDMLIB_UPLOAD",
                        "DDMLIB_INSTALL");
            } else {
                assertHistory(
                        device,
                        "getprop",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "mkdir -p /data/local/tmp/.studio/bin",
                        "chmod +x /data/local/tmp/.studio/bin/installer",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "/system/bin/run-as com.example.simpleapp id -u",
                        "id -u",
                        "/system/bin/cmd package path com.example.simpleapp",
                        "cmd package install-create -r -t -S ${size:com.example.simpleapp}",
                        "cmd package install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple -",
                        "cmd package install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split -",
                        "cmd package install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:CANNOT_GENERATE_DELTA",
                        "INSTALL:OK",
                        "DDMLIB_UPLOAD",
                        "DDMLIB_INSTALL");
            }
        }
    }

    @Test
    public void testAddAsset() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File file = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install", "com.example.simpleapp", file.getAbsolutePath(), "--force-full-install"
        };

        assertEquals(0, runner.run(args, logger));
        assertInstalled("com.example.simpleapp", file);
        assertMetrics(
                runner.getMetrics(),
                "DELTAINSTALL:DISABLED",
                "INSTALL:OK",
                "DDMLIB_UPLOAD",
                "DDMLIB_INSTALL");
        device.getShell().clearHistory();

        file = TestUtils.getWorkspaceFile(BASE + "apks/simple+new_asset.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    file.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };
        int retcode = runner.run(args, logger);
        assertEquals(0, retcode);
        assertEquals(1, device.getApps().size());

        assertInstalled("com.example.simpleapp", file);

        if (device.getApi() < 24) {
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        } else {
            assertHistory(
                    device,
                    "getprop",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "mkdir -p /data/local/tmp/.studio/bin",
                    "chmod +x /data/local/tmp/.studio/bin/installer",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                    "/system/bin/run-as com.example.simpleapp id -u",
                    "id -u",
                    "/system/bin/cmd package path com.example.simpleapp",
                    "/data/local/tmp/.studio/bin/installer -version=$VERSION deltainstall",
                    "/system/bin/cmd package install-create -t -r",
                    "cmd package install-write -S ${size:com.example.simpleapp} 2 base.apk",
                    "/system/bin/cmd package install-commit 2");
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL_UPLOAD",
                    "DELTAINSTALL_INSTALL",
                    "DELTAINSTALL:SUCCESS");
        }
    }

    @Test
    public void testAddAssetWithSplits() throws Exception {
        AssumeUtil.assumeNotWindows(); // This test runs the installer on the host

        assertTrue(device.getApps().isEmpty());
        device.setShellBridge(getShell());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File base = TestUtils.getWorkspaceFile(BASE + "apks/simple.apk");
        File split = TestUtils.getWorkspaceFile(BASE + "apks/split.apk");
        File installersPath = prepareInstaller();

        String[] args = {
            "install",
            "com.example.simpleapp",
            base.getAbsolutePath(),
            split.getAbsolutePath(),
            "--force-full-install"
        };

        int code = runner.run(args, logger);
        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertInstalled("com.example.simpleapp", base, split);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:DISABLED",
                    "INSTALL:OK",
                    "DDMLIB_UPLOAD",
                    "DDMLIB_INSTALL");
        }

        device.getShell().clearHistory();

        File newBase = TestUtils.getWorkspaceFile(BASE + "apks/simple+new_asset.apk");
        args =
                new String[] {
                    "install",
                    "com.example.simpleapp",
                    newBase.getAbsolutePath(),
                    split.getAbsolutePath(),
                    "--installers-path=" + installersPath.getAbsolutePath()
                };

        code = runner.run(args, logger);

        if (device.getApi() < 21) {
            assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal(), code);
            assertMetrics(
                    runner.getMetrics(),
                    "DELTAINSTALL:API_NOT_SUPPORTED",
                    "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21");
        } else {
            assertEquals(0, code);
            assertEquals(1, device.getApps().size());

            // Check new app installed
            assertInstalled("com.example.simpleapp", newBase, split);

            if (device.getApi() < 24) {
                assertHistory(
                        device,
                        "getprop",
                        "pm install-create -r -t -S ${size:com.example.simpleapp}",
                        "pm install-write -S ${size:com.example.simpleapp:base.apk} 2 0_simple_new_asset -",
                        "pm install-write -S ${size:com.example.simpleapp:split_split_01.apk} 2 1_split -",
                        "pm install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL:API_NOT_SUPPORTED",
                        "INSTALL:OK",
                        "DDMLIB_UPLOAD",
                        "DDMLIB_INSTALL");
            } else {
                assertHistory(
                        device,
                        "getprop",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "mkdir -p /data/local/tmp/.studio/bin",
                        "chmod +x /data/local/tmp/.studio/bin/installer",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION dump com.example.simpleapp",
                        "/system/bin/run-as com.example.simpleapp id -u",
                        "id -u",
                        "/system/bin/cmd package path com.example.simpleapp",
                        "/data/local/tmp/.studio/bin/installer -version=$VERSION deltainstall",
                        "/system/bin/cmd package install-create -t -r -p com.example.simpleapp",
                        "cmd package install-write -S ${size:com.example.simpleapp:base.apk} 2 base.apk",
                        "/system/bin/cmd package install-commit 2");
                assertMetrics(
                        runner.getMetrics(),
                        "DELTAINSTALL_UPLOAD",
                        "DELTAINSTALL_INSTALL",
                        "DELTAINSTALL:SUCCESS");
            }
        }
    }

    private static void assertHistory(FakeDevice device, String... expectedHistory)
            throws IOException {
        List<String> actualHistory = device.getShell().getHistory();
        String actual = String.join("\n", actualHistory);
        String expected = String.join("\n", expectedHistory);

        // Apply the right version
        expected = expected.replaceAll("\\$VERSION", Version.hash());

        // Find the right sizes:
        Pattern pattern = Pattern.compile("\\$\\{size:([^:}]*)(:([^:}]*))?}");
        Matcher matcher = pattern.matcher(expected);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String pkg = matcher.group(1);
            String file = matcher.group(3);
            List<String> paths = device.getAppPaths(pkg);
            int size = 0;
            for (String path : paths) {
                if (file == null || path.endsWith("/" + file)) {
                    size += device.readFile(path).length;
                }
            }
            matcher.appendReplacement(buffer, Integer.toString(size));
        }
        matcher.appendTail(buffer);
        expected = buffer.toString();

        assertEquals(expected, actual);
    }

    public File prepareInstaller() throws IOException {
        File root = TestUtils.getWorkspaceRoot();
        String testInstaller = "tools/base/deploy/installer/android-installer/test-installer";
        File installer = new File(root, testInstaller);
        if (!installer.exists()) {
            // Running from IJ
            File devRoot = new File(root, "bazel-genfiles/");
            installer = new File(devRoot, testInstaller);
        }
        File installers = Files.createTempDirectory("installers").toFile();
        File x86 = new File(installers, "x86");
        x86.mkdirs();
        FileUtils.copyFile(installer, new File(x86, "installer"));
        return installers;
    }

    public File getShell() {
        File root = TestUtils.getWorkspaceRoot();
        String path = "tools/base/deploy/installer/bash_bridge";
        File file = new File(root, path);
        if (!file.exists()) {
            // Running from IJ
            file = new File(root, "bazel-bin/" + path);
        }
        return file;
    }

    public void assertInstalled(String packageName, File... files) throws IOException {
        assertArrayEquals(new String[] {packageName}, device.getApps().toArray());
        List<String> paths = device.getAppPaths(packageName);
        assertEquals(files.length, paths.size());
        for (int i = 0; i < paths.size(); i++) {
            byte[] expected = Files.readAllBytes(files[i].toPath());
            assertArrayEquals(expected, device.readFile(paths.get(i)));
        }
    }

    private void assertMetrics(ArrayList<DeployMetric> metrics, String... expected) {
        String[] actual =
                metrics.stream()
                        .map(m -> m.getName() + (m.hasStatus() ? ":" + m.getStatus() : ""))
                        .toArray(String[]::new);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void testBasicSwap() throws Exception {
        // Install the base apk:
        assertTrue(device.getApps().isEmpty());
        ApkFileDatabase db = new SqlApkFileDatabase(File.createTempFile("test_db", ".bin"));
        DeployerRunner runner = new DeployerRunner(db, service);
        File file = TestUtils.getWorkspaceFile(BASE + "signed_app/base.apk");
        String[] args = {"install", "com.android.test.uibench", file.getAbsolutePath()};
        int retcode = runner.run(args, logger);
        assertEquals(0, retcode);
        assertEquals(1, device.getApps().size());
        assertInstalled("com.android.test.uibench", file);

        File installers = Files.createTempDirectory("installers").toFile();
        FileUtils.writeToFile(new File(installers, "x86/installer"), "INSTALLER");

        device.getShell().addCommand(new InstallerCommand());

        file = TestUtils.getWorkspaceFile(BASE + "signed_app/base.apk");
        args =
                new String[] {
                    "codeswap",
                    "com.android.test.uibench",
                    file.getAbsolutePath(),
                    "--installers-path=" + installers.getAbsolutePath()
                };
        retcode = runner.run(args, logger);

        if (device.supportsJvmti()) {
            assertEquals(0, retcode);
        } else {
            assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal(), retcode);
        }
    }

    private class InstallerCommand extends ShellCommand {
        @Override
        public int execute(
                ShellContext context, String[] args, InputStream stdin, PrintStream stdout)
                throws IOException {
            Arguments arguments = new Arguments(args);
            String version = arguments.nextOption();
            // We assume the version is fine
            String action = arguments.nextArgument();
            Deploy.InstallerResponse.Builder builder = Deploy.InstallerResponse.newBuilder();
            switch (action) {
                case "dump":
                    {
                        String pkg = arguments.nextArgument();
                        Deploy.DumpResponse.Builder dump = Deploy.DumpResponse.newBuilder();
                        dump.setStatus(Deploy.DumpResponse.Status.OK);
                        byte[] block =
                                Files.readAllBytes(
                                        TestUtils.getWorkspaceFile(
                                                        BASE + "/signed_app/base.apk.remoteblock")
                                                .toPath());
                        byte[] cd =
                                Files.readAllBytes(
                                        TestUtils.getWorkspaceFile(
                                                        BASE + "/signed_app/base.apk.remotecd")
                                                .toPath());

                        Deploy.PackageDump packageDump =
                                Deploy.PackageDump.newBuilder()
                                        .setName(pkg)
                                        .addProcesses(42)
                                        .addApks(
                                                Deploy.ApkDump.newBuilder()
                                                        .setName("base.apk")
                                                        .setCd(ByteString.copyFrom(cd))
                                                        .setSignature(ByteString.copyFrom(block))
                                                        .build())
                                        .build();
                        dump.addPackages(packageDump);
                        builder.setDumpResponse(dump);
                        break;
                    }
                case "deltapreinstall":
                    {
                        byte[] bytes = new byte[4];
                        ByteStreams.readFully(stdin, bytes);
                        int size = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        bytes = new byte[size];
                        ByteStreams.readFully(stdin, bytes);
                        CodedInputStream cis = CodedInputStream.newInstance(bytes);
                        Deploy.DeltaPreinstallRequest request =
                                Deploy.DeltaPreinstallRequest.parser().parseFrom(cis);

                        Deploy.DeltaPreinstallResponse.Builder preinstall =
                                Deploy.DeltaPreinstallResponse.newBuilder();
                        preinstall.setStatus(Deploy.DeltaPreinstallResponse.Status.OK);
                        preinstall.setSessionId("1234");
                        builder.setDeltapreinstallResponse(preinstall);

                        break;
                    }
                case "swap":
                    {
                        byte[] bytes = new byte[4];
                        ByteStreams.readFully(stdin, bytes);
                        int size = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        bytes = new byte[size];
                        ByteStreams.readFully(stdin, bytes);
                        CodedInputStream cis = CodedInputStream.newInstance(bytes);
                        Deploy.SwapRequest request = Deploy.SwapRequest.parser().parseFrom(cis);

                        Deploy.SwapResponse.Builder swap = Deploy.SwapResponse.newBuilder();
                        swap.setStatus(Deploy.SwapResponse.Status.OK);
                        builder.setSwapResponse(swap);
                        break;
                    }
            }

            Deploy.InstallerResponse response = builder.build();
            int size = response.getSerializedSize();
            byte[] bytes = new byte[Integer.BYTES + size];
            ByteBuffer sizeWritter = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            sizeWritter.putInt(size);
            CodedOutputStream cos = CodedOutputStream.newInstance(bytes, Integer.BYTES, size);
            response.writeTo(cos);
            stdout.write(bytes);
            return 0;
        }

        @Override
        public String getExecutable() {
            return "/data/local/tmp/.studio/bin/installer";
        }
    }
}
