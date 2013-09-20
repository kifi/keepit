package com.keepit.common.admin

import scala.collection.JavaConversions._
import sun.management.ManagementFactory

class ThreadCPUInfo(totalShare: Double, usage: Option[Double])
class StackTraceInfo(className: String, method: String, line: Int, matchesFilter: Boolean) {
  def contains(filter: String) = (className + method).toLowerCase.contains(filter.toLowerCase)
}
class ThreadStatistics(name: String, state: String, stackInfo: Seq[StackTraceInfo]) {
  def stackContains(filter: String) = stackInfo.filter(_.contains(filter.toLowerCase)).nonEmpty
  def nameContains(filter: String) = name.toLowerCase.contains(filter.toLowerCase)
  def stateContains(filter: String) = state.toLowerCase.contains(filter.toLowerCase)
}

object ThreadStatistics {

  def build(nameFilter: String, stateFilter: String, stackFilter: String, cpuSampleMs: Option[Int]) = {
    val lolbean = ManagementFactory.getThreadMXBean()
    def getCpu(tid: Long) = {
      lolbean.getThreadCpuTime(tid)
    }

    val threads = Thread.getAllStackTraces()

    val cpuTimes = threads.map(t => t._1.getId() -> getCpu(t._1.getId()))
    val totalCPUTime = cpuTimes.values.sum

    val cpuSamples = if(cpuSampleMs.isDefined) {
      val samples = (cpuSampleMs.get / 1000) + 1
      val samplesMs = cpuSampleMs.get / Math.max(1, samples)

      var cpuSamples = cpuTimes.map { th =>
        val sample = new Array[Long](samples)
        sample(0) = th._2
        th._1 -> sample
      }
      for (i <- 1 until samples) {
        Thread.sleep(samplesMs)
        threads.foreach { th => cpuSamples(th._1.getId())(i) = getCpu(th._1.getId()) }
      }

      cpuSamples.map { thread =>

      }
      Some(cpuSamples)
    } else None



  }
}
