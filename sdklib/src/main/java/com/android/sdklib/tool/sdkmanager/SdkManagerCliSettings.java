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
package com.android.sdklib.tool.sdkmanager;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.repository.api.Channel;
import com.android.repository.api.ConsoleProgressIndicator;
import com.android.repository.api.Downloader;
import com.android.repository.api.ProgressIndicator;
import com.android.repository.api.RepoManager;
import com.android.repository.api.SettingsController;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@VisibleForTesting
class SdkManagerCliSettings implements SettingsController {

    private static final String CHANNEL_ARG = "--channel=";
    private static final String SDK_ROOT_ARG = "--sdk_root=";
    private static final String INCLUDE_OBSOLETE_ARG = "--include_obsolete";
    private static final String HELP_ARG = "--help";
    private static final String NO_HTTPS_ARG = "--no_https";
    private static final String VERBOSE_ARG = "--verbose";
    private static final String PROXY_TYPE_ARG = "--proxy=";
    private static final String PROXY_HOST_ARG = "--proxy_host=";
    private static final String PROXY_PORT_ARG = "--proxy_port=";
    private static final String NO_PROXY_ARG = "--no_proxy";
    private static final String TOOLSDIR = "com.android.sdklib.toolsdir";

    private static final Map<String, Function<SdkManagerCliSettings, SdkAction>> ARG_TO_ACTION =
            new HashMap<>();

    public static final String STUDIO_UNITTEST_DO_NOT_RESOLVE_PROXY_ENV =
            "STUDIO_UNITTEST_DO_NOT_RESOLVE_PROXY";
    public static final String HTTP_PROXY_ENV = "HTTP_PROXY";
    public static final String HTTPS_PROXY_ENV = "HTTPS_PROXY";

    static {
        InstallAction.register(ARG_TO_ACTION);
        UninstallAction.register(ARG_TO_ACTION);
        LicensesAction.register(ARG_TO_ACTION);
        UpdateAction.register(ARG_TO_ACTION);
        ShowVersionAction.register(ARG_TO_ACTION);
        ListAction.register(ARG_TO_ACTION);
    }

    private Path mLocalPath;

    private SdkAction mAction;

    private int mChannel = 0;
    private boolean mIncludeObsolete = false;
    private boolean mForceHttp = false;
    private boolean mDisableSdkPatches = false;
    private boolean mForceNoProxy = false;
    private boolean mVerbose = false;
    private Proxy.Type mProxyType;
    private SocketAddress mProxyHost;
    private String mProxyHostStr;
    private AndroidSdkHandler mHandler;
    private RepoManager mRepoManager;
    private PrintStream mOut;
    private BufferedReader mIn;
    private Downloader mDownloader;
    private FileSystem mFileSystem;
    private Map<String, String> mEnvironment;

    public void setDownloader(@Nullable Downloader downloader) {
        mDownloader = downloader;
    }

    public void setSdkHandler(@Nullable AndroidSdkHandler handler) {
        mHandler = handler;
        if (handler == null) {
            mRepoManager = null;
        } else {
            // This is just loading the local repo, so no need to show progress here.
            mRepoManager =
                    handler.getSdkManager(new QuietProgressIndicator(getProgressIndicator()));
        }
    }

    public void setOutputStream(@Nullable PrintStream out) {
        mOut = out;
    }

    public void setInputStream(@Nullable InputStream in) {
        mIn = in == null ? null : new BufferedReader(new InputStreamReader(in));
    }

    @Nullable
    public static SdkManagerCliSettings createSettings(
            @NonNull List<String> args, @NonNull FileSystem fileSystem) {
        return createSettings(args, fileSystem, System.getenv());
    }

    @Nullable
    @VisibleForTesting
    static SdkManagerCliSettings createSettings(
            @NonNull List<String> args,
            @NonNull FileSystem fileSystem,
            @NonNull Map<String, String> environment) {
        ProgressIndicator progress =
                new ConsoleProgressIndicator() {
                    @Override
                    public void logInfo(@NonNull String s) {}

                    @Override
                    public void logVerbose(@NonNull String s) {}
                };
        try {
            return new SdkManagerCliSettings(args, fileSystem, environment, progress);
        } catch (Exception e) {
            progress.logWarning("Could not create settings", e);
            return null;
        }
    }

    private boolean setAction(@NonNull SdkAction action, @NonNull ProgressIndicator progress) {
        //noinspection VariableNotUsedInsideIf
        if (mAction != null) {
            progress.logError(
                    "Only one of "
                            + Joiner.on(", ").join(ARG_TO_ACTION.keySet())
                            + " can be specified.");
            return false;
        }
        mAction = action;
        return true;
    }

    @Nullable
    private SocketAddress createAddress(@NonNull String host, int port) {
        if ("1".equals(mEnvironment.get(STUDIO_UNITTEST_DO_NOT_RESOLVE_PROXY_ENV))) {
            return InetSocketAddress.createUnresolved(host, port);
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            return new InetSocketAddress(address, port);
        } catch (UnknownHostException e) {
            getProgressIndicator().logWarning("Failed to resolve host " + host);
            return null;
        }
    }

    @Nullable
    @Override
    public Channel getChannel() {
        return Channel.create(mChannel);
    }

    @Override
    public boolean getForceHttp() {
        return mForceHttp;
    }

    @Override
    public void setForceHttp(boolean force) {
        mForceHttp = force;
    }

    @Override
    public boolean getDisableSdkPatches() {
        return mDisableSdkPatches;
    }

    @Override
    public void setDisableSdkPatches(boolean disable) {
        mDisableSdkPatches = disable;
    }

    @VisibleForTesting
    public boolean getForceNoProxy() {
        return mForceNoProxy;
    }

    @NonNull
    public Path getLocalPath() {
        return mLocalPath;
    }

    @NonNull
    public SdkAction getAction() {
        return mAction;
    }

    public boolean isVerbose() {
        return mVerbose;
    }

    public boolean includeObsolete() {
        return mIncludeObsolete;
    }

    @NonNull
    public ProgressIndicator getProgressIndicator() {
        PrintStream out = getOutputStream();
        if (out == null) {
            out = System.out;
        }
        return new ConsoleProgressIndicator(out, System.err) {
            @Override
            public void logWarning(@NonNull String s, @Nullable Throwable e) {
                if (mVerbose) {
                    super.logWarning(s, e);
                } else {
                    super.logWarning(s, null);
                }
            }

            @Override
            public void logError(@NonNull String s, @Nullable Throwable e) {
                if (mVerbose) {
                    super.logWarning(s, e);
                } else {
                    super.logWarning(s, null);
                }
                throw new SdkManagerCli.UncheckedCommandFailedException();
            }

            @Override
            public void logInfo(@NonNull String s) {
                if (mVerbose) {
                    super.logInfo(s);
                }
            }

            @Override
            public void logVerbose(@NonNull String s) {
                if (mVerbose) {
                    super.logVerbose(s);
                }
            }
        };
    }

    @NonNull
    @Override
    public Proxy getProxy() {
        return mProxyType == null ? Proxy.NO_PROXY : new Proxy(mProxyType, mProxyHost);
    }

    @VisibleForTesting
    @Nullable
    String getProxyHostStr() {
        return mProxyHostStr;
    }

    @Nullable
    public RepoManager getRepoManager() {
        return mRepoManager;
    }

    @Nullable
    public Downloader getDownloader() {
        return mDownloader;
    }

    @Nullable
    public BufferedReader getInputReader() {
        return mIn;
    }

    @Nullable
    public PrintStream getOutputStream() {
        return mOut;
    }

    @Nullable
    public AndroidSdkHandler getSdkHandler() {
        return mHandler;
    }

    @NonNull
    public FileSystem getFileSystem() {
        return mFileSystem;
    }

    @NonNull
    private static Proxy.Type extractProxyType(
            @NonNull String type, @NonNull ProgressIndicator progress) {
        if (type.equals("socks")) {
            return Proxy.Type.SOCKS;
        } else if (type.equals("http") || type.equals("https")) {
            return Proxy.Type.HTTP;
        }

        progress.logWarning("Valid proxy types are \"socks\" and \"http\".");
        throw new IllegalArgumentException();
    }

    private SdkManagerCliSettings(
            @NonNull List<String> args,
            @NonNull FileSystem fileSystem,
            @NonNull Map<String, String> environment,
            @NonNull ProgressIndicator progress) {
        mFileSystem = fileSystem;
        mEnvironment = environment;

        String proxyHost = null;
        int proxyPort = -1;
        String toolsDir = System.getProperty(TOOLSDIR);
        if (toolsDir != null) {
            mLocalPath = fileSystem.getPath(toolsDir).normalize().getParent();
        }

        args = new LinkedList<>(args);
        Iterator<String> argIter = args.iterator();
        // TODO: delegate parsing of arguments to actions that care about those arguments
        while (argIter.hasNext()) {
            String arg = argIter.next();
            if (ARG_TO_ACTION.containsKey(arg)) {
                if (!setAction(ARG_TO_ACTION.get(arg).apply(this), progress)) {
                    throw new IllegalArgumentException();
                }
                argIter.remove();
            } else if (arg.equals(HELP_ARG)) {
                throw new IllegalArgumentException();
            } else if (arg.equals(NO_HTTPS_ARG)) {
                setForceHttp(true);
                argIter.remove();
            } else if (arg.equals(NO_PROXY_ARG)) {
                mForceNoProxy = true;
                argIter.remove();
            } else if (arg.equals(VERBOSE_ARG)) {
                mVerbose = true;
                argIter.remove();
            } else if (arg.equals(INCLUDE_OBSOLETE_ARG)) {
                mIncludeObsolete = true;
                argIter.remove();
            } else if (arg.startsWith(PROXY_HOST_ARG)) {
                proxyHost = arg.substring(PROXY_HOST_ARG.length());
                argIter.remove();
            } else if (arg.startsWith(PROXY_PORT_ARG)) {
                String value = arg.substring(PROXY_PORT_ARG.length());
                try {
                    proxyPort = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    progress.logWarning(String.format("Invalid port \"%s\"", value));
                    throw new IllegalArgumentException();
                }
                argIter.remove();
            } else if (arg.startsWith(PROXY_TYPE_ARG)) {
                String type = arg.substring(PROXY_TYPE_ARG.length());
                mProxyType = extractProxyType(type, progress);
                argIter.remove();
            } else if (arg.startsWith(CHANNEL_ARG)) {
                String value = arg.substring(CHANNEL_ARG.length());
                try {
                    mChannel = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    progress.logWarning(String.format("Invalid channel id \"%s\"", value));
                    throw new IllegalArgumentException();
                }
                argIter.remove();
            } else if (arg.startsWith(SDK_ROOT_ARG)) {
                mLocalPath = fileSystem.getPath(arg.substring(SDK_ROOT_ARG.length()));
                argIter.remove();
            }
        }

        if (mAction == null) {
            mAction = new InstallAction(this);
        }

        for (String arg : args) {
            if (!mAction.consumeArgument(arg, progress)) {
                progress.logWarning(String.format("Unknown argument %s", arg));
                throw new IllegalArgumentException();
            }
        }

        if (!mAction.validate(progress)) {
            throw new IllegalArgumentException();
        }

        if (mLocalPath == null) {
            throw new IllegalArgumentException();
        }

        if (mForceNoProxy && (proxyHost != null || proxyPort != -1 || mProxyType != null)) {
            progress.logWarning(
                    String.format(
                            "None of %1$s, %2$s, and %3$s must be specified if %4$s is.",
                            PROXY_HOST_ARG, PROXY_PORT_ARG, PROXY_TYPE_ARG, NO_PROXY_ARG));
            throw new IllegalArgumentException();
        } else if (proxyHost == null ^ mProxyType == null || proxyPort == -1 ^ mProxyType == null) {
            progress.logWarning(
                    String.format(
                            "All of %1$s, %2$s, and %3$s must be specified if any are.",
                            PROXY_HOST_ARG, PROXY_PORT_ARG, PROXY_TYPE_ARG));
            throw new IllegalArgumentException();
        } else {
            if (mForceNoProxy) {
                return;
            }
            if (proxyHost == null) {
                // Try to take values from the HTTP_PROXY / HTTPS_PROXY environment variables.
                String proxyEnv;
                if (mForceHttp) {
                    proxyEnv = mEnvironment.get(HTTP_PROXY_ENV);
                } else {
                    proxyEnv = mEnvironment.get(HTTPS_PROXY_ENV);
                    if (proxyEnv == null) {
                        proxyEnv = mEnvironment.get(HTTP_PROXY_ENV);
                    }
                }
                if (proxyEnv != null) {
                    try {
                        URL url = new URL(proxyEnv);
                        mProxyType = extractProxyType(url.getProtocol(), progress);
                        proxyHost = url.getHost();
                        proxyPort = url.getPort();
                    } catch (MalformedURLException e) {
                        progress.logWarning(
                                "The proxy server URL extracted from HTTP_PROXY or "
                                        + "HTTPS_PROXY environment variable could not be parsed. "
                                        + "Either specify the correct URL or unset the environment variable.",
                                e);
                        throw new IllegalArgumentException();
                    }
                }
            }
            if (proxyHost != null) {
                SocketAddress address = createAddress(proxyHost, proxyPort);
                if (address == null) {
                    throw new IllegalArgumentException();
                }
                mProxyHost = address;
                mProxyHostStr = proxyHost;
            }
        }
    }
}
