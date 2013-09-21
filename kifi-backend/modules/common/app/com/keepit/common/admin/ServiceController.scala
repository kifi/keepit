package com.keepit.common.admin

import com.keepit.common.zookeeper.ServiceDiscovery
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import play.api.mvc._
import scala.collection.JavaConversions._
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits._

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
      Async {
        SafeFuture {
          val _stack = if(request.queryString.get("42").nonEmpty) "42" else stack
          val hideStack = request.queryString.get("hideStack").nonEmpty
          val cpuTime = if (request.queryString.get("sample").nonEmpty) {
            request.queryString.get("sample").map { s =>
              val t = s.headOption.getOrElse("1000")
              Math.max(Math.min(1000, (if(t == "") "1000" else t).toInt), 10000)
            }
          } else None

          val stats = ThreadStatistics.build(name, state, _stack, cpuTime, hideStack).sortWith((a,b) => a.cpuInfo.usage.getOrElse(0.0) > b.cpuInfo.usage.getOrElse(0.0))
          Ok(stats.map(_.toTSV()).mkString("\n\n"))
        }
       }
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
