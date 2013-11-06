package com.keepit.common.service

import java.net.URL
import com.keepit.common.time._
import play.api.Play
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import java.util.Locale
import play.api.Mode
import play.api.Mode._
import play.api.libs.json._
import scala.concurrent.{Future, promise}
import com.keepit.common.amazon.AmazonInstanceInfo

case class ServiceVersion(val value: String) {
  override def toString(): String = value
}

sealed abstract class ServiceType(val name: String, val shortName: String) {
  def selfCheck() : Future[Boolean] = promise[Boolean].success(true).future
  def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = ServiceStatus.UP
  override def toString(): String = name
}

object ServiceType {
  case object SHOEBOX extends ServiceType("SHOEBOX", "SB")
  case object ELIZA extends ServiceType("ELIZA", "EZ")
  case object HEIMDAL extends ServiceType("HEIMDAL", "HD")
  case object ABOOK extends ServiceType("ABOOK", "AB")
  case object SCRAPER extends ServiceType("SCRAPER", "SC")
  case object DEV_MODE extends ServiceType("DEV_MODE", "DM")
  case object TEST_MODE extends ServiceType("TEST_MODE", "TM")
  case object SEARCH extends ServiceType("SEARCH", "SR") {
    override def healthyStatus(instance: AmazonInstanceInfo): ServiceStatus = instance.instanceType match {
      case AmazonInstanceInfo.small => ServiceStatus.BACKING_UP
      case _ => ServiceStatus.UP
    }
  }

  def fromString(str: String) = str match {
    case SHOEBOX.name => SHOEBOX
    case SEARCH.name => SEARCH
    case ELIZA.name => ELIZA
    case HEIMDAL.name => HEIMDAL
    case ABOOK.name => ABOOK
    case SCRAPER.name => SCRAPER
    case DEV_MODE.name => DEV_MODE
    case TEST_MODE.name => TEST_MODE
  }

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

  val serviceByCode = Map(
    ServiceType.SHOEBOX.name -> ServiceType.SHOEBOX,
    ServiceType.DEV_MODE.name -> ServiceType.DEV_MODE,
    ServiceType.SEARCH.name -> ServiceType.SEARCH,
    ServiceType.ELIZA.name -> ServiceType.ELIZA,
    ServiceType.HEIMDAL.name -> ServiceType.HEIMDAL,
    ServiceType.ABOOK.name -> ServiceType.ABOOK,
    ServiceType.SCRAPER.name -> ServiceType.SCRAPER
  )

  lazy val currentService: ServiceType = playMode match {
    case Mode.Test => ServiceType.TEST_MODE
    case Mode.Dev => ServiceType.DEV_MODE
    case Mode.Prod => serviceByCode(Play.current.configuration.getString("application.name").get)
  }

  lazy val currentVersion: ServiceVersion = playMode match {
    case Mode.Test => ServiceVersion("Test mode service")
    case _ if currentVersionFile.isEmpty => ServiceVersion("dev")
    case _ => ServiceVersion(io.Source.fromURL(currentVersionFile.get).mkString)
  }

  lazy val compilationTime: DateTime = playMode match {
    case Mode.Test => currentDateTime
    case _ if currentVersionFile.isEmpty => currentDateTime
    case _ =>
      val timeStr = io.Source.fromURL(compilationTimeFile.get).mkString
	  DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(zones.PT).parseDateTime(timeStr)
  }

  lazy val baseUrl: String = Play.current.configuration.getString("application.baseUrl").get
}

