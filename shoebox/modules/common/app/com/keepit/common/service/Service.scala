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

case class ServiceVersion(val value: String) {
  override def toString(): String = value
}

sealed abstract class ServiceType(val name: String)

object ServiceType {
  case object SHOEBOX extends ServiceType("SHOEBOX")
  case object SEARCH extends ServiceType("SEARCH")
  case object DEV_MODE extends ServiceType("DEV_MODE")
  case object TEST_MODE extends ServiceType("TEST_MODE")

  def fromString(str: String) = str match {
    case SHOEBOX.name => SHOEBOX
    case SEARCH.name => SEARCH
    case DEV_MODE.name => DEV_MODE
    case TEST_MODE.name => TEST_MODE
  }

  def format[T]: Format[ServiceType] = Format(
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
    ServiceType.SEARCH.name -> ServiceType.SEARCH
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

