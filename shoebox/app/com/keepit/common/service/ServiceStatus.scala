package com.keepit.common.service

sealed abstract class ServiceStatus(val name: String)

object ServiceStatus {
  case object UP extends ServiceStatus("up")
  case object DOWN extends ServiceStatus("down")
  case object STARTING extends ServiceStatus("starting")
  case object STOPPING extends ServiceStatus("stopping")
}
