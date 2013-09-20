package com.keepit.common.controller

import scala.concurrent.ExecutionContext.Implicits.global

import java.net.InetAddress

import play.api.Logger
import play.api.mvc._

object LoggingFilter extends EssentialFilter {

  lazy val host: String = InetAddress.getLocalHost.getHostName

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader) = {
      val start = System.currentTimeMillis

      def logTime(result: PlainResult): Result = {
        val time = System.currentTimeMillis - start
        Logger.info(s"${rh.method} ${rh.uri} took ${time}ms and returned ${result.header.status}")
        result.withHeaders("Request-Time" -> time.toString, "Spaceship" -> host)
      }

      next(rh).map {
        case plain: PlainResult => logTime(plain)
        case async: AsyncResult => async.transform(logTime)
      }
    }
  }
}
