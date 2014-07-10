package com.keepit.common.admin

import com.keepit.common.zookeeper.ServiceDiscovery
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import play.api.mvc._
import scala.collection.JavaConversions._
import com.keepit.common.service.{ ServiceStatus, ServiceClient, ServiceType, FortyTwoServices }
import com.keepit.common.cache.InMemoryCachePlugin

class ServiceController @Inject() (
    serviceDiscovery: ServiceDiscovery,
    service: FortyTwoServices,
    localCache: InMemoryCachePlugin) extends com.keepit.common.controller.ServiceController with Logging {

  override lazy val serviceType: ServiceType = service.currentService

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

  def upForDeployment = Action { implicit request =>
    if (serviceDiscovery.amIUp) Ok("")
    else ServiceUnavailable(serviceDiscovery.myStatus.map(_.name).getOrElse("NA"))
  }

  def deprecatedUp = upForDeployment

  def upForElb = Action { implicit request =>
    if (serviceDiscovery.myStatus.exists(ServiceStatus.UpForElasticLoadBalancer.contains)) Ok("")
    else ServiceUnavailable(serviceDiscovery.myStatus.map(_.name).getOrElse("NA"))
  }

  def upForPingdom = Action { implicit request =>
    if (serviceDiscovery.myStatus.exists(ServiceStatus.UpForPingdom.contains)) Ok("")
    else ServiceUnavailable(serviceDiscovery.myStatus.map(_.name).getOrElse("NA"))
  }

  def threadDetails(name: String = "", state: String = "", stack: String = "", sort: String = "") = Action { request =>
    if (request.queryString.get("help").nonEmpty) {
      Ok("""
             |Usage: http://server:9000/internal/common/threadDetails
             |Options:
             |    name=$       filter threads by thread name containing $, default: ''
             |    state=$      filter threads by state containing $, default: ''
             |    stack=$      filter threads by stack trace containing $, default: ''
             |    sort         sort results by name, cpu, or share, default: 'name'
             |    [sample=$]   sample cpu for usage, default: not set, if present, 1000
             |    [42]         shortcut to `stack=com.keepit`, default: not set
             |    [hideStack]  hide stacktrace in results, default: not set
             |    [short]      display only the most relevant stack trace element, default: not set
             |
             |Common usages:
             |    http://server:9000/internal/common/threadDetails?42
             |    http://server:9000/internal/common/threadDetails?sample&short
             |    http://server:9000/internal/common/threadDetails?stack=controller
             |
             |See: https://team42.atlassian.net/wiki/display/ENG/View+thread+details+of+a+running+server
             |
             |""".stripMargin)
    } else {
      val _stack = if (request.queryString.get("42").nonEmpty) "42" else stack
      val hideStack = request.queryString.get("hideStack").nonEmpty
      val short = request.queryString.get("short").nonEmpty
      val cpuTime = if (request.queryString.get("sample").nonEmpty) {
        request.queryString.get("sample").map { s =>
          val t = s.headOption.getOrElse("1000")
          Math.min(Math.max(1000, (if (t == "") "1000" else t).toInt), 10000)
        }
      } else None

      val stats = ThreadStatistics.build(name, state, _stack, cpuTime, hideStack, short)
      val largestName = if (stats.nonEmpty) stats.map(f => f.name.length).max else 0

      val statsSorted = sort match {
        case "" | "cpu" if cpuTime.isDefined =>
          stats.sortWith((a, b) => a.cpuInfo.usage.getOrElse(0.0) > b.cpuInfo.usage.getOrElse(0.0))
        case "share" =>
          stats.sortWith((a, b) => a.cpuInfo.totalShare > b.cpuInfo.totalShare)
        case _ => // name
          stats.sortWith((a, b) => a.name.toLowerCase < b.name.toLowerCase)
      }

      Ok(statsSorted.map(_.toTSV(largestName)).mkString("\n\n"))
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
      .sortWith((a, b) => a._2 > b._2)
      .map(th => s"${th._2}\t${th._1}")
      .mkString("\n")

    Ok(displayOut + "\n\n")
  }

  def removeAllFromLocalCache(prefix: Option[String]) = Action { request =>
    localCache.removeAll(prefix)
    Ok
  }

}
