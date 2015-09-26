package com.keepit.common.controller

import play.api.mvc.{ Results, Result, RequestHeader, Filter }

import scala.concurrent.Future

object BlockingFilter extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val ip = requestHeader.headers.get("X-Forwarded-For").getOrElse(requestHeader.remoteAddress)
    if (blocked.contains(ip)) Future.successful(Results.Redirect("/logout"))
    else nextFilter(requestHeader)
  }

  val blocked = Seq(
    "103.60.176.6", // User 97229
    "166.177.248.29" // Léo's phone
  )
}
