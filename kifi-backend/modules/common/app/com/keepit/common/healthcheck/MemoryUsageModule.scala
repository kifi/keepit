package com.keepit.common.healthcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provider, Provides, Singleton }
import java.lang.management.MemoryPoolMXBean

trait MemoryUsageModule extends ScalaModule

case class ProdMemoryUsageModule() extends MemoryUsageModule {
  def configure() {}

  @Singleton
  @Provides
  def memoryUsageMonitorProvider(airbrakeNotifierProvider: Provider[AirbrakeNotifier]): MemoryUsageMonitor = {
    MemoryUsageMonitor { (pool, threshold, maxHeapSize, count) =>
      if (count > 1) { // at least two incidents in a row
        airbrakeNotifierProvider.get.notify(s"LOW MEMORY!!! - pool=[${pool.getName}] threshold=$threshold maxHeapSize=$maxHeapSize count=$count")
      }
    }
  }

}

case class DevMemoryUsageModule() extends MemoryUsageModule {
  def configure() {}

  @Singleton
  @Provides
  def memoryUsageMonitorProvider(airbrakeNotifierProvider: Provider[AirbrakeNotifier]): MemoryUsageMonitor = {
    MemoryUsageMonitor { (pool, threshold, maxHeapSize, count) =>
      if (count > 1) { // at least two incidents in a row
        airbrakeNotifierProvider.get.notify(s"LOW MEMORY!!! - pool=[${pool.getName}] threshold=$threshold maxHeapSize=$maxHeapSize count=$count")
      }
    }
  }
}
