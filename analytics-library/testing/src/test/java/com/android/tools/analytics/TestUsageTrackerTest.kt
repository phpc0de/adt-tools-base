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

import com.android.testutils.VirtualTimeScheduler
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Tests for [TestUsageTracker].  */
class TestUsageTrackerTest {

  private lateinit var scheduler: VirtualTimeScheduler
  private lateinit var testUsageTracker: TestUsageTracker

  @Before
  fun before() {
    // first ensure our default is the NullUsageTracker.
    var tracker = UsageTracker.writerForTest
    assertSame(NullUsageTracker::class.java, tracker.javaClass)

    scheduler = VirtualTimeScheduler()

    // advance time to ensure we have a uptime different from reported time.
    scheduler.advanceBy(1, TimeUnit.MILLISECONDS)

    // create the test usage tracker and set the global instance.
    testUsageTracker = TestUsageTracker(scheduler)
    UsageTracker.setWriterForTest(testUsageTracker)

    // ensure the global instance is the one we just set.
    tracker = UsageTracker.writerForTest
    assertEquals(testUsageTracker, tracker)
  }

  @After
  fun after() {
    // ensure that cleaning the instance puts us back in the initial state.
    UsageTracker.cleanAfterTesting()
    val tracker = UsageTracker.writerForTest
    assertSame(NullUsageTracker::class.java, tracker.javaClass)
  }

  @Test
  fun testUsageTrackerTest() {
    // move time forward to ensure the report time is different from start time.
    scheduler.advanceBy(1, TimeUnit.MILLISECONDS)

    // log an event
    testUsageTracker.logNow(
      AndroidStudioEvent.newBuilder().setKind(AndroidStudioEvent.EventKind.META_METRICS))

    // ensure that that event is what our test usage tracker reports.
    assertEquals(1, testUsageTracker.usages.size.toLong())
    val usage = testUsageTracker.usages[0]
    assertEquals(AndroidStudioEvent.EventKind.META_METRICS, usage.studioEvent.kind)

    // ensure that virtual time has moved as we instructed.
    assertEquals(TimeUnit.MILLISECONDS.toNanos(2), usage.timestamp)
    assertEquals(2, usage.logEvent.eventTimeMs)
  }

  @Test
  fun testLogWithCustomTime() {
    scheduler.advanceBy(1100, TimeUnit.MILLISECONDS)
    // log first event
    testUsageTracker.logNow(
      AndroidStudioEvent.newBuilder().setKind(AndroidStudioEvent.EventKind.META_METRICS))
    scheduler.advanceBy(1000, TimeUnit.MILLISECONDS)

    // log second event with timestamp before the first event
    testUsageTracker.logAt(101, AndroidStudioEvent.newBuilder().setKind(AndroidStudioEvent.EventKind.STUDIO_CRASH))
    scheduler.advanceBy(1000, TimeUnit.MILLISECONDS)

    val usages = testUsageTracker.usages
    assertEquals(2, usages.size.toLong())
    assertEquals(1101, usages[0].logEvent.eventTimeMs)
    assertEquals(101, usages[1].logEvent.eventTimeMs)
  }
}
