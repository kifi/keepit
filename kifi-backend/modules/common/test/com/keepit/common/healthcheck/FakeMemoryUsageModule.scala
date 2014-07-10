package com.keepit.common.healthcheck

import com.google.inject.{ Provides, Singleton }
import java.lang.management.MemoryPoolMXBean

case class FakeMemoryUsageModule() extends MemoryUsageModule {
  def configure() {}

  @Singleton
  @Provides
  def memoryUsageMonitorProvider(airbrakeNotifier: AirbrakeNotifier): MemoryUsageMonitor = new FakeMemoryUsageMonitor()
}

class FakeMemoryUsageMonitor() extends MemoryUsageMonitor {
  def start() {}
  override val monitoredPools = Seq.empty[MemoryUsageMonitor.MemoryPool]
}
