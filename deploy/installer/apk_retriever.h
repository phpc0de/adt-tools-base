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

#ifndef INSTALLER_APKRETRIEVER_H
#define INSTALLER_APKRETRIEVER_H

#include <string>
#include <vector>

namespace deploy {

using Apks = std::vector<std::string>;

class ApkRetriever {
 public:
  explicit ApkRetriever(const std::string& packageName);
  Apks& get();

 private:
  // Retrieve the apks for the packageName_. Try to use "cmd package" first and
  // "pm" second.
  void retrieve() noexcept;
  Apks apks_;
  std::string packageName_;
};

}  // namespace deploy

#endif  // INSTALLER_APKRETRIEVER_H
