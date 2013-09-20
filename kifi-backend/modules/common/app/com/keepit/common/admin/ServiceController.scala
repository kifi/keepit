package com.keepit.common.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.zookeeper.{ServiceDiscovery, ServiceInstance, ServiceCluster}
import com.keepit.common.service.{ServiceType, ServiceStatus}
import com.keepit.common.amazon.{AmazonInstanceInfo}
import com.google.inject.Inject
import views.html
import com.keepit.common.logging.Logging
import play.api.mvc._
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex
import scala.collection.JavaConversions._
import sun.management.ManagementFactory

class ServiceController @Inject() (
    serviceDiscovery: ServiceDiscovery) extends Controller with Logging {

    def forceRefresh = Action { implicit request =>
        serviceDiscovery.forceUpdate()
        Ok("Alright, alright! I've refreshed.")
    }

    def myStatus = Action { implicit request =>
        Ok(serviceDiscovery.myStatus.map(_.name).getOrElse("none"))
    }

    def topology = Action { implicit request =>
        Ok(serviceDiscovery.toString)
    }


    def threadDetails(name: String = "", state: String = "", stack: String = "") = Action { request =>
      val onlyShowUs = request.queryString.get("us").nonEmpty
      val hideStack = request.queryString.get("hideStack").nonEmpty
      val cpuTime = request.queryString.get("cpuSample").map(_.head.toInt)

      val threads = Thread.getAllStackTraces()

      val cpuTimes = threads.map(t => t._1.getId() -> getCpu(t._1.getId()))
      val totalCPUTime = cpuTimes.values.sum
      val (secondCpuTimes, diffCpuTime) = if (cpuTime.nonEmpty) {
        Thread.sleep(cpuTime.get)
        val newTimes = threads.map(t => t._1.getId() -> getCpu(t._1.getId()))
        val diffTime = newTimes.values.sum - totalCPUTime
        (Some(newTimes), Some(diffTime))
      } else (None, None)

      val allThreads = threads.map { case (thread, stackTrace) =>
        val threadName = thread.getName
        val isOurCodeInThisStack = stackTrace.filter(s => (s.getClassName + s.getMethodName).toLowerCase.contains("com.keepit")).nonEmpty

        if ((!onlyShowUs || isOurCodeInThisStack)
          && (threadName.toLowerCase.contains(name.toLowerCase) && thread.getState.toString.toLowerCase.contains(state.toLowerCase))
          && (stack.isEmpty || stackTrace.filter(s => (s.getClassName + s.getMethodName).toLowerCase.contains(stack.toLowerCase)).nonEmpty)) {

          val cpuShare = (cpuTimes.getOrElse(thread.getId(), 0L).toDouble / totalCPUTime.toDouble) * 100
          val header = f"${thread.getName}\t${thread.getState.toString}\t$cpuShare%1.2f%" +
            (if (cpuTime.nonEmpty) {
              val tid = thread.getId()
              val threadCpuShare = ((secondCpuTimes.get(tid) - cpuTimes(tid)).toDouble / diffCpuTime.get.toDouble) * 100
              f"\t$threadCpuShare%1.2f%"
            } else "")
          val stackStr = if(!hideStack) stackTrace.map { st =>
            val matchName = (st.getClassName + st.getMethodName).toLowerCase
            val comKeepitMatch = if (matchName.contains("com.keepit.")) "*" else ""
            val stackMatch = if (stack.nonEmpty && matchName.contains(stack.toLowerCase)) "*"
            else ""
            s"$comKeepitMatch\t$stackMatch\t${st.getClassName}.${st.getMethodName}:${st.getLineNumber}"
          } mkString("\n") else ""
          Some(header + "\n" + stackStr)

        } else None
      }.flatten.mkString("\n\n")
      Ok(allThreads + "\n\n")
    }

    private val lolbean = ManagementFactory.getThreadMXBean()
    private def getCpu(tid: Long) = {
      lolbean.getThreadCpuTime(tid)
    }

    def threadSummary = Action { request =>
      val poolRe = "(.*)-\\d*".r
      val ioRe = "(.*) #\\d*".r
      val allThreads = Thread.getAllStackTraces.map { th =>
        val threadName = th._1.getName
        threadName match {
          case poolRe(shortName) => shortName
          case ioRe(shortName) => shortName
          case s => s
        }
      }
      val displayOut = allThreads
        .map(th => (th, allThreads.count(_ == th)))
        .toSet.toSeq
        .sortWith((a,b) => a._2 > b._2)
        .map(th => s"${th._2}\t${th._1}")
        .mkString("\n")

      Ok(displayOut + "\n\n")
    }

}
