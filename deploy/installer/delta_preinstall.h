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

#ifndef DELTA_PUSH_H_
#define DELTA_PUSH_H_

#include "tools/base/deploy/installer/command.h"

#include <string>

#include "tools/base/deploy/installer/workspace.h"

namespace deploy {

class DeltaPreinstallCommand : public Command {
 public:
  DeltaPreinstallCommand(Workspace& workspace) : Command(workspace) {}
  virtual ~DeltaPreinstallCommand() {}
  virtual void ParseParameters(int argc, char** argv);
  virtual void Run();

 private:
  bool SendApkToPackageManager(const proto::PatchInstruction& patch,
                               const std::string& session_id);
  proto::DeltaPreinstallRequest request_;
};

}  // namespace deploy

#endif  // DELTA_PUSH_H_
