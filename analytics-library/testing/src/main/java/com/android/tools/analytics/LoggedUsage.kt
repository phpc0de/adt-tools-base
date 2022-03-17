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
package com.android.tools.analytics

import com.google.protobuf.InvalidProtocolBufferException
import com.google.wireless.android.play.playlog.proto.ClientAnalytics
import com.google.wireless.android.sdk.stats.AndroidStudioEvent

/** Describes a usage tracking event as captured by [TestUsageTracker]  */
data class LoggedUsage @Throws(InvalidProtocolBufferException::class) constructor(
    val timestamp: Long,
    val logEvent: ClientAnalytics.LogEvent) {
  val studioEvent: AndroidStudioEvent = AndroidStudioEvent.parseFrom(logEvent.sourceExtension)
}
