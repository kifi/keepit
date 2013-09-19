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

      val allThreads = Thread.getAllStackTraces.map { case (thread, stackTrace) =>
        val threadName = thread.getName
        val isOurCodeInThisStack = stackTrace.filter(s => (s.getClassName + s.getMethodName).toLowerCase.contains("com.keepit")).nonEmpty

        if (!onlyShowUs || isOurCodeInThisStack) {
          if (threadName.toLowerCase.contains(name.toLowerCase) && thread.getState.toString.toLowerCase.contains(state.toLowerCase)) {
            if (stack.isEmpty || stackTrace.filter(s => (s.getClassName + s.getMethodName).toLowerCase.contains(stack.toLowerCase)).nonEmpty) {
              val header = s"${thread.getName}\t(${thread.getState.toString})"
              val stackStr = stackTrace.map { st =>
                val matchName = (st.getClassName + st.getMethodName).toLowerCase
                val comKeepitMatch = if (matchName.contains("com.keepit.")) "*" else ""
                val stackMatch = if (stack.nonEmpty && matchName.contains(stack.toLowerCase)) "*"
                else ""
                s"$comKeepitMatch\t$stackMatch\t${st.getClassName}.${st.getMethodName}:${st.getLineNumber}"
              } mkString("\n")
              Some(header + "\n" + stackStr)
            } else None
          } else None
        } else None
      }.flatten.mkString("\n\n")
      Ok(allThreads)
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

      Ok(displayOut)
    }

}
