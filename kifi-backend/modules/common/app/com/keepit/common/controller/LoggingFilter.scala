package com.keepit.common.controller

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import java.net.InetAddress

import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._
import play.api.mvc._

object LoggingFilter extends EssentialFilter {

  lazy val host: String = InetAddress.getLocalHost.getHostName
  lazy val accessLog = new AccessLog()

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader) = {
      val timer = AccessLogTimer(HTTP_IN)

      def logTime(result: PlainResult): Result = {
        val trackingId = rh.headers.get(CommonHeaders.TrackingId).getOrElse("NA")
        val event = accessLog.add(timer.done(
          trackingId = trackingId,
          method = rh.method,
          url = rh.uri,
          remoteHost = rh.remoteAddress,
          targetHost = rh.host,
          statusCode = result.header.status
        ))
        result.withHeaders(
          CommonHeaders.ResponseTime -> event.duration.toString,
          //todo(eishay): the interesting part is the local service type and node id, to be sent
          CommonHeaders.LocalHost -> host)
      }

      next(rh).map {
        case plain: PlainResult => logTime(plain)
        case async: AsyncResult => async.transform(logTime)
      }
    }
  }
}
