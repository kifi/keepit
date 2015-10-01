package com.keepit.common.controller

import play.api.mvc.{ Results, Result, RequestHeader, Filter }

import scala.concurrent.Future

object BlockingFilter extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val ip = requestHeader.headers.get("X-Forwarded-For").getOrElse(requestHeader.remoteAddress)
    if (blocked.contains(ip) && !requestHeader.path.contains("logout") && requestHeader.session.get(KifiSession.FORTYTWO_USER_ID).isDefined) Future.successful(Results.Redirect("/logout"))
    else nextFilter(requestHeader)
  }

  val blocked = Seq(
    "103.60.176.6",
    "103.60.176.238",
    "43.249.225.14"
  )
}
