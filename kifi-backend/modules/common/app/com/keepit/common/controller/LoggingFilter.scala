package com.keepit.common.controller

import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import java.net.InetAddress

import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._
import com.keepit.FortyTwoGlobal
import com.keepit.common.zookeeper.ServiceDiscovery

import play.api.mvc._
import play.api.Play

class LoggingFilter() extends EssentialFilter {

  lazy val global = Play.current.global.asInstanceOf[FortyTwoGlobal]
  lazy val accessLog = global.injector.instance[AccessLog]
  lazy val discovery = global.injector.instance[ServiceDiscovery]

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader) = {
      val timer = accessLog.timer(HTTP_IN)
      def logTime(result: PlainResult): Result = {
        val trackingId = rh.headers.get(CommonHeaders.TrackingId).getOrElse(null)
        val remoteServiceId = rh.headers.get(CommonHeaders.LocalServiceId).getOrElse(null)
        val remoteIsLeader = rh.headers.get(CommonHeaders.IsLeader).getOrElse(null)
        val remoteServiceType = rh.headers.get(CommonHeaders.LocalServiceType).getOrElse(null)
        val event = accessLog.add(timer.done(
          trackingId = trackingId,
          remoteLeader = remoteIsLeader,
          remoteServiceId = remoteServiceId,
          remoteServiceType = remoteServiceType,
          method = rh.method,
          url = rh.uri,
          statusCode = result.header.status
        ))
        result.withHeaders(
          CommonHeaders.ResponseTime -> event.duration.toString,
          CommonHeaders.IsLeader -> (if(discovery.isLeader()) "Y" else "N"),
          CommonHeaders.LocalServiceId -> discovery.thisInstance.map(_.id.id.toString).getOrElse("NA"))
      }

      next(rh).map {
        case plain: PlainResult => logTime(plain)
        case async: AsyncResult => async.transform(logTime)
      }
    }
  }
}
