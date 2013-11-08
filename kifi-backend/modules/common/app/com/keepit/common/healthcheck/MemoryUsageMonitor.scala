package com.keepit.common.healthcheck

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
  val percentThreshold = 0.8d

  def apply(warn: (MemoryPoolMXBean, Long, Long, Int) => Unit): MemoryUsageMonitor = new MemoryUsageMonitorImpl(warn)

  def apply(airbrakeNotifier: AirbrakeNotifier): MemoryUsageMonitor = {
    apply{ (pool, threshold, maxHeapSize, count) =>
      if (count > 1) { // at least two incidents in a row
        airbrakeNotifier.notify(s"LOW MEMORY!!! - pool=[${pool.getName}] threshold=$threshold maxHeapSize=$maxHeapSize count=$count")
      }
    }
  }
}

trait MemoryUsageMonitor {
  def start(): Unit
}

class MemoryUsageMonitorImpl(warn: (MemoryPoolMXBean, Long, Long, Int) => Unit) extends MemoryUsageMonitor with Logging {

  case class MemoryPool(bean: MemoryPoolMXBean, threshold: Long, maxHeapSize: Long)

  val monitoredPools = ManagementFactory.getMemoryPoolMXBeans.flatMap{ pool =>
    if (pool.getType == MemoryType.HEAP && MemoryUsageMonitor.poolsToBeMonitored.contains(pool.getName)) {
      val maxHeapSize = pool.getUsage.getMax
      val threshold = (maxHeapSize.toDouble * MemoryUsageMonitor.percentThreshold).toInt
      var supported = false

      if (pool.isUsageThresholdSupported) {
        pool.setUsageThreshold(threshold)
        supported = true
      } else {
        log.warn(s"${pool.getName} does not support a usage threshold")
      }

      if (pool.isCollectionUsageThresholdSupported) {
        pool.setCollectionUsageThreshold(threshold)
        supported = true
      } else {
        log.warn(s"${pool.getName} does not support a collection usage threshold")
      }

      if(supported) Some(MemoryPool(pool, threshold, maxHeapSize)) else None
    } else {
      None
    }
  }

  private[this] val started = new AtomicBoolean(false)
  private[this] var collectionUsageThresholdCount = 0

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      def listener = new NotificationListener {
        def handleNotification(notification: Notification , handback: Object) {
          val memPool = if (handback != null && handback.isInstanceOf[MemoryPool]) handback.asInstanceOf[MemoryPool] else null
          notification.getType match {
            case MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED =>
              collectionUsageThresholdCount = 0  // clear since the memory usage was below threshold after the last GC
            case MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED =>
              collectionUsageThresholdCount += 1
              warn(memPool.bean, memPool.threshold, memPool.maxHeapSize, collectionUsageThresholdCount)
            case _ =>
          }
        }
      }

      val emitter = ManagementFactory.getMemoryMXBean.asInstanceOf[NotificationEmitter]
      monitoredPools.foreach{ memPool => emitter.addNotificationListener(listener, null, memPool) }
    }
  }
}
