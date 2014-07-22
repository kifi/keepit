package com.keepit.common.healthcheck

import com.google.inject.Provider
import com.keepit.common.logging.Logging
import java.lang.management.ManagementFactory
import java.lang.management.MemoryNotificationInfo
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.util.concurrent.atomic.AtomicBoolean
import javax.management.Notification
import javax.management.NotificationListener
import javax.management.NotificationEmitter
import scala.collection.JavaConversions._

object MemoryUsageMonitor {

  val poolsToBeMonitored = Set("CMS Old Gen")

  // The following default is used when -XX:CMSInitiatingOccupancyFraction is not set.
  // It is encouraged to set -XX:CMSInitiatingOccupancyFraction explicitly.
  val percentThresholdDefault = 0.92d

  case class MemoryPool(bean: MemoryPoolMXBean, threshold: Long, maxHeapSize: Long)

  def apply(warn: (MemoryPoolMXBean, Long, Long, Int) => Unit): MemoryUsageMonitor = new MemoryUsageMonitorImpl(warn)

  def apply(airbrakeNotifierProvider: Provider[AirbrakeNotifier]): MemoryUsageMonitor = {
    val monitor = apply { (pool, threshold, maxHeapSize, count) =>
      if (count > 1) { // at least two incidents in a row
        airbrakeNotifierProvider.get.notify(s"LOW MEMORY!!! - pool=[${pool.getName}] threshold=$threshold maxHeapSize=$maxHeapSize count=$count")
      }
    }
    if (monitor.monitoredPools.isEmpty) airbrakeNotifierProvider.get.notify(s"found no memory pool to monitor")
    monitor
  }
}

trait MemoryUsageMonitor {
  def start(): Unit
  val monitoredPools: Seq[MemoryUsageMonitor.MemoryPool]
}

class MemoryUsageMonitorImpl(warn: (MemoryPoolMXBean, Long, Long, Int) => Unit) extends MemoryUsageMonitor with Logging {
  import MemoryUsageMonitor._

  private val percentThreshold: Double = {
    val runtimeMxBean = ManagementFactory.getRuntimeMXBean()
    val jvmArgs = runtimeMxBean.getInputArguments()
    val threshold = jvmArgs.find(_.startsWith("-XX:CMSInitiatingOccupancyFraction=")) match {
      case Some(arg) => arg.split("=")(1).toDouble / 100.0d
      case None => percentThresholdDefault
    }

    log.info(s"percentThreshold=$threshold")
    threshold
  }

  override val monitoredPools = ManagementFactory.getMemoryPoolMXBeans.flatMap { pool =>
    if (pool.getType == MemoryType.HEAP && poolsToBeMonitored.contains(pool.getName)) {
      val maxHeapSize = pool.getUsage.getMax
      val threshold = (maxHeapSize.toDouble * percentThreshold).toLong

      if (pool.isUsageThresholdSupported && pool.isCollectionUsageThresholdSupported) {
        pool.setUsageThreshold(threshold)
        pool.setCollectionUsageThreshold(threshold)

        Some(MemoryPool(pool, threshold, maxHeapSize))
      } else {
        log.warn(s"${pool.getName} does not support either usage threshold or collection usage threshold")

        None
      }
    } else {
      None
    }
  }

  private[this] val started = new AtomicBoolean(false)
  private[this] var collectionUsageThresholdCount = 0

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      def listener = new NotificationListener {
        def handleNotification(notification: Notification, handback: Object) {
          val memPool = if (handback != null && handback.isInstanceOf[MemoryPool]) handback.asInstanceOf[MemoryPool] else null
          notification.getType match {
            case MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED =>
              collectionUsageThresholdCount = 0 // clear since the memory usage was below threshold after the last GC
            case MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED =>
              collectionUsageThresholdCount += 1
              warn(memPool.bean, memPool.threshold, memPool.maxHeapSize, collectionUsageThresholdCount)
            case _ =>
          }
        }
      }

      val emitter = ManagementFactory.getMemoryMXBean.asInstanceOf[NotificationEmitter]
      monitoredPools.foreach { memPool => emitter.addNotificationListener(listener, null, memPool) }
    }
  }
}
