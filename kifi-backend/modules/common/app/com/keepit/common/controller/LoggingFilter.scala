package com.keepit.common.controller

import scala.concurrent.ExecutionContext.Implicits.global

import java.net.InetAddress

import play.api.Logger
import play.api.mvc._

object LoggingFilter extends EssentialFilter {

  lazy val host: String = InetAddress.getLocalHost.getHostName
  lazy val accessLog = Logger("com.keepit.access")

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader) = {
      val start = System.currentTimeMillis

      def logTime(result: PlainResult): Result = {
        val time = System.currentTimeMillis - start
        val trackingId = rh.headers.get(CommonHeaders.TrackingId).getOrElse("NA")
        accessLog.info(
          s"[HTTP-IN] #${trackingId} [${rh.method}] ${rh.uri} from ${rh.remoteAddress} to ${rh.host} took [${time}ms] and returned ${result.header.status}")
        result.withHeaders(
          CommonHeaders.ResponseTime -> time.toString,
          CommonHeaders.LocalHost -> host)
      }

      next(rh).map {
        case plain: PlainResult => logTime(plain)
        case async: AsyncResult => async.transform(logTime)
      }
    }
  }
}
