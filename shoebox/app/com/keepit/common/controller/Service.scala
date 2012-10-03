package com.keepit.common.controller

import play.api.Play.current
import play.api.Play
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import java.util.Locale

case class ServiceVersion(val value: String) {
  override def toString(): String = value
}

sealed abstract class ServiceType(val name: String) 

object ServiceType {
  case object SHOEBOX extends ServiceType("SHOEBOX")
  case object DEV_MODE extends ServiceType("DEV_MODE")
}

object FortyTwoServices {
  val serviceByCode = Map(
    ServiceType.SHOEBOX.name -> ServiceType.SHOEBOX,
    ServiceType.DEV_MODE.name -> ServiceType.DEV_MODE
  )
  
  lazy val currentService: ServiceType = serviceByCode(Play.current.configuration.getString("application.name").get)
  
  lazy val currentVersion: ServiceVersion = ServiceVersion(io.Source.fromURL(Play.resource("app_version.txt").get).mkString)
  
  lazy val compilationTime: DateTime = {
    val timeStr = io.Source.fromURL(Play.resource("app_compilation_date.txt").get).mkString
    val PT = DateTimeZone.forID("America/Los_Angeles")
    DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(PT).parseDateTime(timeStr)
  }
}

