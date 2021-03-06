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
 *
 */

#include <memory>

#include <jni.h>
#include <jvmti.h>

#include "tools/base/deploy/agent/native/capabilities.h"
#include "tools/base/deploy/agent/native/jni/jni_util.h"
#include "tools/base/deploy/agent/native/swapper.h"
#include "tools/base/deploy/common/event.h"
#include "tools/base/deploy/common/log.h"
#include "tools/base/deploy/common/socket.h"

namespace deploy {

// Watch as I increment between runs!
static int run_counter = 0;

// Event that fires when the agent hooks onto a running VM.
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* input,
                                                 void* reserved) {
  jvmtiEnv* jvmti;
  JNIEnv* jni;

  InitEventSystem();

  Log::V("Prior agent invocations in this VM: %d", run_counter++);

  if (vm->GetEnv((void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
    ErrEvent("Error retrieving JVMTI function table.");
    return JNI_OK;
  }

  if (vm->GetEnv((void**)&jni, JNI_VERSION_1_2) != JNI_OK) {
    ErrEvent("Error retrieving JNI function table.");
    return JNI_OK;
  }

  if (jvmti->AddCapabilities(&REQUIRED_CAPABILITIES) != JVMTI_ERROR_NONE) {
    ErrEvent("Error setting capabilities.");
    return JNI_OK;
  }

  std::unique_ptr<deploy::Socket> socket(new deploy::Socket());

  if (!socket->Open()) {
    ErrEvent("Could not open new socket");
    return JNI_OK;
  }

  if (!socket->Connect(input)) {
    ErrEvent("Could not connect to socket");
    return JNI_OK;
  }

  Swapper& swapper = Swapper::Instance();
  swapper.Initialize(jvmti, std::move(socket));
  swapper.StartSwap(jni);

  // We return JNI_OK even if the hot swap fails, since returning JNI_ERR just
  // causes ART to attempt to re-attach the agent with a null classloader.
  return JNI_OK;
}

}  // namespace deploy
