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

#include <algorithm>
#include <iostream>
#include <map>
#include <sstream>
#include <string>

#include <getopt.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifdef __APPLE__
#include <mach-o/dyld.h>
#endif

#include "tools/base/bazel/native/matryoshka/doll.h"
#include "tools/base/deploy/common/event.h"
#include "tools/base/deploy/common/log.h"
#include "tools/base/deploy/common/utils.h"
#include "tools/base/deploy/installer/command_cmd.h"
#include "tools/base/deploy/installer/dump.h"
#include "tools/base/deploy/installer/executor_impl.h"
#include "tools/base/deploy/installer/package_manager.h"
#include "tools/base/deploy/installer/redirect_executor.h"
#include "tools/base/deploy/installer/workspace.h"
#include "tools/base/deploy/proto/deploy.pb.h"

using namespace deploy;

struct Parameters {
  const char* binary_name = nullptr;
  const char* command_name = nullptr;
  const char* cmd_path = nullptr;
  const char* pm_path = nullptr;
  const char* version = nullptr;
  const char* shell = nullptr;
  const char* shell_arg = nullptr;
  const char* root_directory = nullptr;
  int consumed = 0;
};

std::string GetStringUsage(const char* invoked_path) {
  std::stringstream buffer;
  buffer << "Usage:" << std::endl
         << invoked_path << " [env parameters] command [command_parameters]"
         << std::endl
         << std::endl
         << "Environment parameters available:" << std::endl
         << "  -cmd=X: Define path to cmd executable (to mock android)."
         << std::endl
         << "  -pm=X : Define path to package manager executable (to mock "
            "android)."
         << std::endl
         << "  -shell=X : Define path to a shell-like executable (to mock "
            "android)."
         << std::endl
         << "  -shell-arg=X : An argument to the custom shell before the "
            "command (to mock android)."
         << std::endl
         << "  -root=X : The root directory to use (to mock android)."
         << std::endl
         << "  -version=X : Program will fail if version != X." << std::endl
         << "Commands available:" << std::endl
         << "   dump : Extract CDs and Signatures for a given applicationID."
         << std::endl
         << "   swap : Perform a hot-swap via JVMTI." << std::endl
         << std::endl;
  return buffer.str();
}

bool ParseParameters(int argc, char** argv, Parameters* parameters) {
  parameters->binary_name = argv[0];
  parameters->consumed = 1;

  int index = 1;
  while (index < argc && argv[index][0] == '-') {
    strtok(argv[index], "=");
    if (!strncmp("-cmd", argv[index], 4)) {
      parameters->cmd_path = strtok(nullptr, "=");
    } else if (!strncmp("-pm", argv[index], 3)) {
      parameters->pm_path = strtok(nullptr, "=");
    } else if (!strncmp("-shell-arg", argv[index], 10)) {
      parameters->shell_arg = strtok(nullptr, "=");
    } else if (!strncmp("-shell", argv[index], 6)) {
      parameters->shell = strtok(nullptr, "=");
    } else if (!strncmp("-version", argv[index], 8)) {
      parameters->version = strtok(nullptr, "=");
    } else if (!strncmp("-root", argv[index], 5)) {
      parameters->root_directory = strtok(nullptr, "=");
    } else {
      std::cerr << "environment parameter unknown:" << argv[index] << std::endl;
      return false;
    }
    parameters->consumed++;
    index++;
  }
  if (index < argc) {
    parameters->command_name = argv[index];
    parameters->consumed++;
  }
  return true;
}

std::string GetInstallerPath() {
#ifdef __APPLE__
  uint32_t size = 1024;
  char dest[size];
  std::fill(dest, dest + size, '\0');
  _NSGetExecutablePath(dest, &size);
#else
  int size = 1024;
  char dest[size];
  std::fill(dest, dest + size, '\0');
  readlink("/proc/self/exe", dest, size);
#endif
  return std::string(dest);
}

int Fail(proto::InstallerResponse_Status status, Workspace& workspace,
         const std::string& message) {
  workspace.GetResponse().set_status(status);
  ErrEvent(message);
  workspace.SendResponse();
  return EXIT_FAILURE;
}

std::string GetVersion() {
  static std::string version = "";

  if (!version.empty()) {
    return version;
  }

  std::vector<std::unique_ptr<matryoshka::Doll>> dolls;
  if (!matryoshka::Open(dolls)) {
    return "UNMATRYOSHKAED";
  }

  for (auto& doll : dolls) {
    if (doll->name == "version") {
      return std::string((char*)doll->content, doll->content_len);
    }
  }

  return "UNVERSIONED";
}

int main(int argc, char** argv) {
  InitEventSystem();
  BeginPhase("installer");

  ExecutorImpl executor;
  Workspace workspace(GetInstallerPath(), &executor);

  // Check and parse parameters
  if (argc < 2) {
    std::string message = GetStringUsage(argv[0]);
    return Fail(proto::InstallerResponse::ERROR_PARAMETER, workspace, message);
  }
  Parameters parameters;
  bool parametersParsed = ParseParameters(argc, argv, &parameters);
  if (!parametersParsed) {
    std::string message = GetStringUsage(argv[0]);
    return Fail(proto::InstallerResponse::ERROR_PARAMETER, workspace, message);
  }
  if (parameters.cmd_path != nullptr) {
    CmdCommand::SetPath(parameters.cmd_path);
  }
  if (parameters.pm_path != nullptr) {
    PackageManager::SetPath(parameters.pm_path);
  }
  std::string shell;
  std::string shell_arg;
  if (parameters.shell != nullptr && parameters.shell_arg != nullptr) {
    shell = parameters.shell;
    shell_arg = parameters.shell_arg;
  }
  RedirectExecutor redirect(shell, shell_arg, executor);
  if (parameters.shell != nullptr && parameters.shell_arg != nullptr) {
    workspace.SetExecutor(&redirect);
  }
  if (parameters.root_directory != nullptr) {
    workspace.SetRoot(parameters.root_directory);
  }

  // Verify that this program is the version the called expected.
  if (parameters.version != nullptr &&
      strcmp(parameters.version, GetVersion().c_str())) {
    std::string message = "Version mismatch. Requested:"_s +
                          parameters.version + "but have " + GetVersion();
    return Fail(proto::InstallerResponse::ERROR_WRONG_VERSION, workspace,
                message);
  }

  // Retrieve Command to be invoked.
  auto task = GetCommand(parameters.command_name, workspace);
  if (task == nullptr) {
    return Fail(proto::InstallerResponse::ERROR_CMD, workspace,
                "Unknown command");
  }

  // Allow command to parse its parameters and invoke it.
  task->ParseParameters(argc - parameters.consumed, argv + parameters.consumed);
  if (!task->ReadyToRun()) {
    std::string message =
        "Command "_s + parameters.command_name + ": wrong parameters";
    return Fail(proto::InstallerResponse::ERROR_PARAMETER, workspace, message);
  }

  // Create a workspace for filesystem operations.
  if (!workspace.Valid()) {
    return Fail(proto::InstallerResponse::ERROR_CMD, workspace,
                "Bad workspace");
  }

  // Finally! Run !
  task->Run();
  workspace.GetResponse().set_status(proto::InstallerResponse::OK);
  EndPhase();
  workspace.SendResponse();
  return EXIT_SUCCESS;
}
