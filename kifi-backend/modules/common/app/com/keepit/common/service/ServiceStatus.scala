package com.keepit.common.service

import play.api.libs.json._

sealed abstract class ServiceStatus(val name: String)

object ServiceStatus {
  case object UP extends ServiceStatus("up")
  case object DOWN extends ServiceStatus("down")
  case object STARTING extends ServiceStatus("starting")
  case object STOPPING extends ServiceStatus("stopping")
  case object SICK extends ServiceStatus("sick")
  case object SELFCHECK_FAIL extends ServiceStatus("helthcheck_fail")

  def fromString(str: String) = str match {
    case UP.name => UP
    case DOWN.name => DOWN
    case STARTING.name => STARTING
    case STOPPING.name => STOPPING
    case SICK.name => SICK
    case SELFCHECK_FAIL.name => SELFCHECK_FAIL
  }

  implicit def format[T]: Format[ServiceStatus] = Format(
    __.read[String].map(fromString),
    new Writes[ServiceStatus]{ def writes(o: ServiceStatus) = JsString(o.name)}
  )

}
