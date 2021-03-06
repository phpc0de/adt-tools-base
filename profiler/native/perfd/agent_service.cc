/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include "agent_service.h"

using grpc::ServerContext;
using profiler::proto::HeartBeatRequest;
using profiler::proto::HeartBeatResponse;

namespace profiler {

grpc::Status AgentServiceImpl::HeartBeat(ServerContext* context,
                                         const HeartBeatRequest* request,
                                         HeartBeatResponse* response) {
  auto now = daemon_->clock()->GetCurrentTime();
  daemon_->SetHeartBeatTimestamp(request->pid(), now);
  return grpc::Status::OK;
}

}  // namespace profiler
