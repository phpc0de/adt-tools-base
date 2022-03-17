package com.android.tools.analytics

import com.android.testutils.VirtualTimeDateProvider
import com.android.testutils.VirtualTimeScheduler
import com.android.utils.FileUtils
import com.google.common.io.Files
import com.google.protobuf.InvalidProtocolBufferException
import com.google.wireless.android.play.playlog.proto.ClientAnalytics
import java.io.File
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

/**
 * An implementation of [UsageTracker] for use in tests. Allows introspection of the logged
 * usages via [.getUsages]
 */
class TestUsageTracker(val scheduler: VirtualTimeScheduler) : UsageTrackerWriter() {
  val usages = ArrayList<LoggedUsage>()
  private val androidSdkHomeEnvironment: File
  private var closeException: RuntimeException? = null

  private val logger: Logger
    get() = Logger.getLogger("#TestUsageTracker")

  init {
    // in order to ensure reproducible anonymized values & timestamps are reported,
    // set a date provider based on the virtual time scheduler.
    val dateProvider = VirtualTimeDateProvider(scheduler)
    AnalyticsSettings.dateProvider = dateProvider
    androidSdkHomeEnvironment = Files.createTempDir()
    EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(androidSdkHomeEnvironment.path)
  }

  override fun logDetails(logEvent: ClientAnalytics.LogEvent.Builder) {
    try {
      usages.add(LoggedUsage(scheduler.currentTimeNanos, logEvent.build()))
    }
    catch (e: InvalidProtocolBufferException) {
      throw RuntimeException(
        "Expecting a LogEvent that contains an AndroidStudioEvent proto", e)
    }

  }

  @Throws(Exception::class)
  override fun close() {
    if (closeException != null) {
      logger.log(Level.SEVERE, "Re-closing TestUsageTracker. Last closed by:", closeException)
      throw closeException!!
    }
    closeException = RuntimeException("Last TestUsageTracker close")

    // Clean up the virtual time data provider after the test is done.
    val dateProvider = VirtualTimeDateProvider(scheduler)
    AnalyticsSettings.dateProvider = dateProvider
    FileUtils.deleteDirectoryContents(androidSdkHomeEnvironment)
    Environment.instance = Environment.SYSTEM
  }
}
