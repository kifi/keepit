package com.keepit.common.service

import play.api.libs.json._

sealed abstract class ServiceStatus(val name: String)

object ServiceStatus {
  case object UP extends ServiceStatus("up")
  case object STARTING extends ServiceStatus("starting")
  case object STOPPING extends ServiceStatus("stopping")
  case object OFFLINE extends ServiceStatus("offline")
  case object SICK extends ServiceStatus("sick")
  case object SELFCHECK_FAIL extends ServiceStatus("helthcheck_fail")
  case object BACKING_UP extends ServiceStatus("backing_up")

  def fromString(str: String) = str match {
    case UP.name => UP
    case STARTING.name => STARTING
    case STOPPING.name => STOPPING
    case OFFLINE.name => OFFLINE
    case SICK.name => SICK
    case SELFCHECK_FAIL.name => SELFCHECK_FAIL
    case BACKING_UP.name => BACKING_UP
  }

  val UpForPingdom = Seq(UP, STOPPING, SICK, SELFCHECK_FAIL)
  val UpForElasticLoadBalancer = Seq(UP, SICK, SELFCHECK_FAIL)

  implicit def format[T]: Format[ServiceStatus] = Format(
    __.read[String].map(fromString),
    new Writes[ServiceStatus] { def writes(o: ServiceStatus) = JsString(o.name) }
  )

}
