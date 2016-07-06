package com.keepit.common.service

import java.net.URL
import com.keepit.common.time._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.util.Locale
import play.api.Mode
import play.api.Mode._
import play.api.libs.json._
import scala.concurrent.{ Future, Promise }
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.inject.FortyTwoConfig

object ServiceVersion {
  val pattern = """([0-9]{8})-([0-9]{4})-([0-9a-zA-Z_\-\\\$]*)-([0-9a-z]*)""".r
}

case class ServiceVersion(value: String) {
  override def toString: String = value
  val ServiceVersion.pattern(date, time, branch, hash) = value
}

sealed abstract class ServiceType(val name: String, val shortName: String, val loadFactor: Int = 1, val isCanary: Boolean = false) {
  def selfCheck(): Future[Boolean] = Promise[Boolean]().success(true).future
  def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = ServiceStatus.UP
  override def toString: String = name

  val minInstances: Int = 1
  val warnInstances: Int = 2
}

object ServiceType {
  case object SHOEBOX extends ServiceType("SHOEBOX", "SB") {
    override def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = {
      val capabilities = instance.capabilities
      if (capabilities.contains(ServiceStatus.OFFLINE.name)) ServiceStatus.OFFLINE else ServiceStatus.UP
    }
    override val minInstances = 1
    override val warnInstances = 2
  }
  case object ELIZA extends ServiceType("ELIZA", "EZ") {
    override val minInstances = 1
    override val warnInstances = 2
  }
  case object HEIMDAL extends ServiceType("HEIMDAL", "HD", loadFactor = 2) {
    override val minInstances = 0
    override val warnInstances = 0
  }
  case object ABOOK extends ServiceType("ABOOK", "AB", loadFactor = 2)
  case object DEV_MODE extends ServiceType("DEV_MODE", "DM")
  case object TEST_MODE extends ServiceType("TEST_MODE", "TM")
  case object SEARCH extends ServiceType("SEARCH", "SR") {
    override def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = {
      val capabilities = instance.capabilities
      if (capabilities.contains("backup") && !capabilities.contains("search")) ServiceStatus.BACKING_UP else ServiceStatus.UP
    }

    override val minInstances = 2
    override val warnInstances = 4
  }

  case object GRAPH extends ServiceType("GRAPH", "GR", loadFactor = 2) {
    override def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = {
      val capabilities = instance.capabilities
      if (capabilities.contains("backup")) ServiceStatus.BACKING_UP else ServiceStatus.UP
    }
    override val minInstances = 0
    override val warnInstances = 0
  }

  case object C_SHOEBOX extends ServiceType("C_SHOEBOX", "C_SB", loadFactor = 1, true) {
    override val minInstances = 0
    override val warnInstances = 0
  }

  case object CORTEX extends ServiceType("CORTEX", "CT", loadFactor = 5) {
    override val minInstances = 0
    override val warnInstances = 0
  }

  case object ROVER extends ServiceType("ROVER", "RO", loadFactor = 5)

  // Possible initialization cycle/deadlock when one of the case objects above is first dereferenced before the ServiceType object
  lazy val inProduction: List[ServiceType] = SEARCH :: SHOEBOX :: ELIZA :: HEIMDAL :: ABOOK :: CORTEX :: GRAPH :: ROVER :: Nil
  lazy val notInProduction: List[ServiceType] = DEV_MODE :: TEST_MODE :: C_SHOEBOX :: Nil
  lazy val all: List[ServiceType] = inProduction ::: notInProduction
  lazy val fromString: Map[String, ServiceType] = all.map(serviceType => serviceType.name -> serviceType).toMap

  implicit def format[T]: Format[ServiceType] = Format(
    __.read[String].map(fromString),
    new Writes[ServiceType] { def writes(o: ServiceType) = JsString(o.name) }
  )
}

class FortyTwoServices(
    clock: Clock,
    playMode: Mode,
    compilationTimeFile: Option[URL],
    currentVersionFile: Option[URL],
    fortytwoConfig: FortyTwoConfig) {

  val started = clock.now

  lazy val currentService: ServiceType = playMode match {
    case Mode.Test => ServiceType.TEST_MODE
    case Mode.Dev => ServiceType.DEV_MODE
    case Mode.Prod => ServiceType.fromString(fortytwoConfig.applicationName)
  }

  lazy val currentVersion: ServiceVersion = playMode match {
    case Mode.Test => ServiceVersion("20140123-1713-TEST-77f17ab")
    case _ if currentVersionFile.isEmpty => ServiceVersion("dev")
    case _ => ServiceVersion(io.Source.fromURL(currentVersionFile.get).mkString)
  }

  lazy val compilationTime: DateTime = playMode match {
    case Mode.Test => currentDateTime
    case _ if currentVersionFile.isEmpty => currentDateTime
    case _ =>
      val timeStr = io.Source.fromURL(compilationTimeFile.get).mkString
      DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(zones.UTC).parseDateTime(timeStr)
  }

  lazy val baseUrl: String = fortytwoConfig.applicationBaseUrl
}

