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

#ifndef AGENT_CONFIG_H
#define AGENT_CONFIG_H

#include <memory>
#include <string>

#include "deploy.pb.h"

namespace deploy {

std::unique_ptr<proto::SwapRequest> ParseFromString(
    const std::string& request_string);

}  // namespace deploy

#endif
