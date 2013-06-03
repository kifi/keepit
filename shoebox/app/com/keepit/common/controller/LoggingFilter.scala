package com.keepit.common.controller

import play.api.mvc._
import play.api.Logger
import scala.concurrent.ExecutionContext.Implicits.global

object LoggingFilter extends EssentialFilter {
  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader) = {
      val start = System.currentTimeMillis

      def logTime(result: PlainResult): Result = {
        val time = System.currentTimeMillis - start
        Logger.info(s"${rh.method} ${rh.uri} took ${time}ms and returned ${result.header.status}")
        result.withHeaders("Request-Time" -> time.toString)
      }

      next(rh).map {
        case plain: PlainResult => logTime(plain)
        case async: AsyncResult => async.transform(logTime)
      }
    }
  }
}
