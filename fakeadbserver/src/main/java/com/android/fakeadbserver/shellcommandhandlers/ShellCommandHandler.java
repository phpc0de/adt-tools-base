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

package com.android.fakeadbserver.shellcommandhandlers;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.fakeadbserver.CommandHandler;
import com.android.fakeadbserver.DeviceState;
import com.android.fakeadbserver.FakeAdbServer;
import java.net.Socket;

/**
 * ShellCommandHandlers is a pre-supplied convenience construct to plug in and handle custom shell
 * commands. This reflects the "shell:command" local service a stated in the ADB protocol.
 */
public abstract class ShellCommandHandler extends CommandHandler {

    /**
     * This is the main execution method of the command.
     *
     * @param fakeAdbServer  Fake ADB Server itself.
     * @param responseSocket Socket for this connection.
     * @param device         Target device for the command, if any.
     * @param args           Arguments for the command, if any.
     * @return a boolean, with true meaning keep the connection alive, false to close the connection
     */
    public abstract boolean invoke(@NonNull FakeAdbServer fakeAdbServer,
            @NonNull Socket responseSocket, @NonNull DeviceState device, @Nullable String args);
}
