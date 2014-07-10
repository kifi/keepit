package com.keepit.common.admin

import scala.collection.JavaConversions._
import java.lang.management.ManagementFactory

case class ThreadCPUInfo(totalShare: Double, usage: Option[Double]) {
  def toTSV() = {
    usage match {
      case Some(percent) => f"$percent%1.2fPER cpu / $totalShare%1.2fPER share".replaceAll("PER", "%")
      case None => f"$totalShare%1.2fPER share".replaceAll("PER", "%")
    }
  }
}
case class StackTraceInfo(className: String, method: String, line: Int, matchesFilter: String) {
  def contains(filter: String) = (className + method).toLowerCase.contains(filter.toLowerCase)

  def toTSV() = {
    s"\n$matchesFilter\t$className.$method:$line"
  }
}
case class ThreadStatistics(name: String, state: String, stackInfo: Seq[StackTraceInfo], cpuInfo: ThreadCPUInfo, id: Long) {
  def stackContains(filter: String) = stackInfo.filter(_.contains(filter.toLowerCase)).nonEmpty
  def nameContains(filter: String) = name.toLowerCase.contains(filter.toLowerCase)
  def stateContains(filter: String) = state.toLowerCase.contains(filter.toLowerCase)

  def toTSV(largestName: Int = 0, charsPerTab: Int = 8) = {
    val nameTabs = "\t" * ((-(largestName % charsPerTab) + (name.length % charsPerTab) + largestName - name.length) / charsPerTab)
    val stateTabs = "\t" * (if (state.length < 8) 1 else 0)
    s"$name$nameTabs\t$state$stateTabs\t${cpuInfo.toTSV()}${stackInfo.map(_.toTSV).mkString("")}"
  }
}

object ThreadStatistics {

  def build(nameFilter: String, stateFilter: String, stackFilter: String, cpuSampleMs: Option[Int], hideStack: Boolean, short: Boolean) = {
    val onlyShowUs = stackFilter == "42"

    val threads = Thread.getAllStackTraces().toMap

    val cpuUsage = sampleThreadCPU(threads, cpuSampleMs)

    threads.map {
      case (thread, stackTrace) =>
        val threadName = thread.getName
        val isOurCodeInThisStack = stackTrace.filter(s => (s.getClassName + s.getMethodName).toLowerCase.contains("com.keepit")).nonEmpty

        if ((!onlyShowUs || isOurCodeInThisStack)
          && (threadName.toLowerCase.contains(nameFilter.toLowerCase) && thread.getState.toString.toLowerCase.contains(stateFilter.toLowerCase))
          && (stackFilter.isEmpty || stackTrace.filter(s => (s.getClassName + s.getMethodName).toLowerCase.contains(stateFilter.toLowerCase)).nonEmpty)) {

          val cpuShare = cpuUsage.get(thread.getId()).map(_._1).getOrElse(0.0)

          val cpuInfo = new ThreadCPUInfo(cpuShare, cpuUsage.get(thread.getId()).map(_._2).flatten)

          val header = f"${thread.getName}\t${thread.getState.toString}\t$cpuShare%1.2fPER".replaceAll("PER", "%")
          val stackInfos = if (!hideStack) {
            val _stackTrace = if (short) {
              val filtered = Seq("java.lang.Thread", "sun.misc", "java.util.concurrent", "scala.concurrent", "akka.util.internal", "sun.nio.ch", "java.lang.Object", "java.lang.ref", "org.jboss.netty")
              stackTrace.collectFirst {
                case st if st.getClassName.contains("com.keepit") => Seq(st)
              }.orElse(stackTrace.collectFirst {
                case st if filtered.filter(st.getClassName.startsWith).isEmpty => Seq(st)
              }).getOrElse(Seq())
            } else stackTrace.toSeq

            _stackTrace.map { st =>
              val matches = if (stackNoCaseMatch(st, stackFilter)) "*"
              else if (isOurCodeInThisStack && stackNoCaseMatch(st, "com.keepit")) "#"
              else ""
              new StackTraceInfo(st.getClassName, st.getMethodName, st.getLineNumber, matches)
            }.toSeq
          } else Seq()
          Some(new ThreadStatistics(thread.getName, thread.getState.toString, stackInfos, cpuInfo, thread.getId))

        } else None
    }.flatten.toSeq
  }

  val lolbean = ManagementFactory.getThreadMXBean()
  private def getCpu(tid: Long) = {
    lolbean.getThreadCpuTime(tid)
  }

  private def stackNoCaseMatch(st: StackTraceElement, filter: String) = filter.nonEmpty && (st.getClassName + st.getMethodName).toLowerCase.contains(filter.toLowerCase)

  private def sampleThreadCPU(threads: Map[Thread, Array[StackTraceElement]], cpuSampleMs: Option[Int]): Map[Long, (Double, Option[Double])] = {
    val threadsCount = threads.size
    val initialTimes = {
      val _initialTimes = new Array[(Long, Long)](threadsCount)
      var i = 0
      val iter = threads.iterator
      while (iter.hasNext) {
        val th = iter.next._1.getId
        _initialTimes(i) = th -> getCpu(th)
        i += 1
      }
      _initialTimes.toMap
    }

    val totalCPUTime1 = initialTimes.map(r => if (r._2 > 0) r._2 else 0).sum

    if (cpuSampleMs.isDefined) {
      Thread.sleep(cpuSampleMs.get)
      val newTimes = {
        val _newTimes = new Array[(Long, Long)](threadsCount)
        var i = 0
        val iter = threads.iterator
        while (iter.hasNext) {
          val th = iter.next._1.getId
          _newTimes(i) = th -> getCpu(th)
          i += 1
        }
        _newTimes.toMap
      }

      val totalDiff = newTimes.map { r =>
        if (initialTimes(r._1) > 0L && r._2 > 0L) r._2 - initialTimes(r._1)
        else 0
      }.sum
      threads.map {
        case (thread, st) =>
          val tid = thread.getId()
          val tCpu = {
            if (newTimes.get(tid).getOrElse(0L) > 0L && initialTimes.get(tid).getOrElse(0L) > 0L) {
              Some(((newTimes.get(tid).getOrElse(0L) - initialTimes.get(tid).getOrElse(0L)).toDouble / totalDiff) * 100)
            } else None
          }
          tid -> ((initialTimes(tid).toDouble / totalCPUTime1) * 100, tCpu)
      }
    } else threads.map {
      case (thread, _) =>
        thread.getId -> ((initialTimes(thread.getId).toDouble / totalCPUTime1) * 100, None)
    }
  }
}
