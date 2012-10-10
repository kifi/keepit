package com.keepit.common.controller

import com.keepit.common.time._
import play.api.Play.current
import play.api.Play
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import java.util.Locale
import play.api.Mode

case class ServiceVersion(val value: String) {
  override def toString(): String = value
}

sealed abstract class ServiceType(val name: String) 

object ServiceType {
  case object SHOEBOX extends ServiceType("SHOEBOX")
  case object DEV_MODE extends ServiceType("DEV_MODE")
  case object TEST_MODE extends ServiceType("TEST_MODE")
}

object FortyTwoServices {
  val serviceByCode = Map(
    ServiceType.SHOEBOX.name -> ServiceType.SHOEBOX,
    ServiceType.DEV_MODE.name -> ServiceType.DEV_MODE
  )
  
  lazy val currentService: ServiceType = current.mode match {
    case Mode.Test => ServiceType.TEST_MODE
    case Mode.Dev => ServiceType.DEV_MODE
    case Mode.Prod => serviceByCode(Play.current.configuration.getString("application.name").get)
  }
  
  lazy val currentVersion: ServiceVersion = current.mode match {
    case Mode.Test => ServiceVersion("Test mode service")
    case _ => ServiceVersion(io.Source.fromURL(Play.resource("app_version.txt").get).mkString)
  }
  
  lazy val compilationTime: DateTime = current.mode match {
    case Mode.Test => currentDateTime
    case _ =>
      val timeStr = io.Source.fromURL(Play.resource("app_compilation_date.txt").get).mkString
	  val PT = DateTimeZone.forID("America/Los_Angeles")
	  DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(PT).parseDateTime(timeStr)
  }
}

