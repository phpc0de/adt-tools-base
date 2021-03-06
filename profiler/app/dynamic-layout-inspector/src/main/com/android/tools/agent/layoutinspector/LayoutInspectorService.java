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

package com.android.tools.agent.layoutinspector;

import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * This (singleton) class can register a callback to, whenever a view is updated, send the current
 * skia picture back to studio.
 */
@SuppressWarnings("unused") // invoked via jni
public class LayoutInspectorService {
    private final Properties properties = new Properties();

    private static LayoutInspectorService sInstance;

    public static LayoutInspectorService instance() {
        if (sInstance == null) {
            sInstance = new LayoutInspectorService();
        }
        return sInstance;
    }

    private LayoutInspectorService() {}

    /**
     * Creates a payload with the given message and id, and sends an event containing that id to
     * Studio.
     */
    private native void sendSkiaPicture(byte[] message, int len, int id);

    /** This method is called when a layout inspector command is recieved by the agent. */
    @SuppressWarnings("unused") // invoked via jni
    public void onStartLayoutInspectorCommand() {
        try {
            Class<?> windowInspector = Class.forName("android.view.inspector.WindowInspector");
            Method getViewsMethod = windowInspector.getMethod("getGlobalWindowViews");
            final View root = ((List<View>) getViewsMethod.invoke(null)).get(0);

            Class viewDebug = ViewDebug.class;

            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            final Callable<OutputStream> callable =
                    new Callable<OutputStream>() {
                        @Override
                        public OutputStream call() {
                            return os;
                        }
                    };

            final Executor realExecutor = Executors.newSingleThreadExecutor();

            final Executor executor =
                    new Executor() {
                        @Override
                        public void execute(final Runnable command) {
                            realExecutor.execute(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            command.run();
                                            byte[] arr = os.toByteArray();
                                            sendSkiaPicture(
                                                    arr,
                                                    arr.length,
                                                    (int) System.currentTimeMillis());
                                            os.reset();
                                        }
                                    });
                        }
                    };

            final Method startCaptureMethod =
                    viewDebug.getMethod(
                            "startRenderingCommandsCapture",
                            View.class,
                            Executor.class,
                            Callable.class);

            root.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                startCaptureMethod.invoke(null, root, executor, callable);
                                root.invalidate();
                            } catch (Throwable e) {
                                sendErrorMessage(e);
                            }
                        }
                    });

        } catch (Throwable e) {
            sendErrorMessage(e);
        }
    }

    /** Sends the properties via the agent. */
    private native void sendProperties(long event, long viewId);

    /**
     * This method is called when a layout inspector command is recieved by the agent.
     *
     * @param viewId the uniqueDrawingId on the view which is the same id used in the skia image
     * @param event a handle to an PropertyEvent protobuf to pass back in native calls
     */
    @SuppressWarnings("unused") // invoked via jni
    public void onGetPropertiesInspectorCommand(long viewId, long event) {
        try {
            View root = findRootView();
            View view = findViewById(root, viewId);
            if (view == null) {
                return;
            }
            properties.loadProperties(view, event);
            sendProperties(event, viewId);
        } catch (Throwable ex) {
            sendErrorMessage(ex);
        }
    }

    private View findRootView() {
        try {
            Class<?> windowInspector = Class.forName("android.view.inspector.WindowInspector");
            Method getViewsMethod = windowInspector.getMethod("getGlobalWindowViews");
            List<View> views = (List<View>) getViewsMethod.invoke(null);
            return views.isEmpty() ? null : views.get(0);
        } catch (Throwable ex) {
            return null;
        }
    }

    private View findViewById(View parent, long id) {
        if (parent != null && getUniqueDrawingId(parent) == id) {
            return parent;
        }
        if (!(parent instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) parent;
        int count = group.getChildCount();
        for (int index = 0; index < count; index++) {
            View found = findViewById(group.getChildAt(index), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private long getUniqueDrawingId(View view) {
        try {
            // TODO: Call this method directly when we compile against android-Q
            Method method = View.class.getDeclaredMethod("getUniqueDrawingId");
            Long layoutId = (Long) method.invoke(view);
            return layoutId != null ? layoutId : 0;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            return 0;
        }
    }

    /** Sends an LayoutInspector Event with an error message back to Studio */
    private native void sendErrorMessage(String message);

    private void sendErrorMessage(Throwable e) {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(error));
        sendErrorMessage(error.toString());
    }
}
