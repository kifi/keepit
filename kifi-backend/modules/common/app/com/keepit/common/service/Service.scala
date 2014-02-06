package com.keepit.common.service

import java.net.URL
import com.keepit.common.time._
import play.api.Play
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.util.Locale
import play.api.Mode
import play.api.Mode._
import play.api.libs.json._
import scala.concurrent.{Future, promise}
import com.keepit.common.amazon.{AmazonInstanceType, AmazonInstanceInfo}

object ServiceVersion {
  val pattern = """([0-9]{8})-([0-9]{4})-([0-9a-zA-Z_]*)-([0-9a-z]*)""".r
}

case class ServiceVersion(val value: String) {
  override def toString(): String = value
  lazy val ServiceVersion.pattern(date, time, branch, hash) = value
}

sealed abstract class ServiceType(val name: String, val shortName: String, val isCanary: Boolean = false) {
  def selfCheck() : Future[Boolean] = promise[Boolean].success(true).future
  def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = ServiceStatus.UP
  override def toString(): String = name

  val minInstances  : Int = 1
  val warnInstances : Int = 2
}

object ServiceType {
  case object SHOEBOX extends ServiceType("SHOEBOX", "SB")
  case object ELIZA extends ServiceType("ELIZA", "EZ")
  case object HEIMDAL extends ServiceType("HEIMDAL", "HD")
  case object ABOOK extends ServiceType("ABOOK", "AB")
  case object SCRAPER extends ServiceType("SCRAPER", "SC") {
    override val minInstances  = 1
    override val warnInstances = 2
  }
  case object DEV_MODE extends ServiceType("DEV_MODE", "DM")
  case object TEST_MODE extends ServiceType("TEST_MODE", "TM")
  case object SEARCH extends ServiceType("SEARCH", "SR") {
    override def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = instance.instantTypeInfo match {
      case AmazonInstanceType.M1Small => ServiceStatus.BACKING_UP
      case _ => ServiceStatus.UP
    }

    override val minInstances = 2
    override val warnInstances = 4
  }
  case object C_SHOEBOX extends ServiceType("C_SHOEBOX", "C_SB", true) {
    override val minInstances = 0
    override val warnInstances = 0
  }

  // Possible initialization cycle/deadlock when one of the case objects above is first dereferenced before the ServiceType object
  lazy val inProduction: List[ServiceType] =  SEARCH :: SHOEBOX :: ELIZA :: HEIMDAL :: ABOOK :: SCRAPER :: Nil
  lazy val notInProduction: List[ServiceType] = DEV_MODE :: TEST_MODE :: C_SHOEBOX :: Nil
  lazy val all: List[ServiceType] = inProduction ::: notInProduction
  lazy val fromString: Map[String, ServiceType] = all.map(serviceType => (serviceType.name -> serviceType)).toMap

  implicit def format[T]: Format[ServiceType] = Format(
    __.read[String].map(fromString),
    new Writes[ServiceType]{ def writes(o: ServiceType) = JsString(o.name)}
  )
}

class FortyTwoServices(
  clock: Clock,
  playMode: Mode,
  compilationTimeFile: Option[URL],
  currentVersionFile: Option[URL]) {

  val started = clock.now

  lazy val currentService: ServiceType = playMode match {
    case Mode.Test => ServiceType.TEST_MODE
    case Mode.Dev => ServiceType.DEV_MODE
    case Mode.Prod => ServiceType.fromString(Play.current.configuration.getString("application.name").get)
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

  lazy val baseUrl: String = Play.current.configuration.getString("application.baseUrl").get
}

