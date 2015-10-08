package com.keepit.common.controller

import scala.concurrent.Promise
import play.api.mvc.{ Results, Result, RequestHeader, Filter }

import scala.concurrent.Future

object BlockingFilter extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val ip = requestHeader.headers.get("X-Forwarded-For").getOrElse(requestHeader.remoteAddress)
    if (blocked.contains(ip) && !requestHeader.path.contains("logout") && requestHeader.session.get(KifiSession.FORTYTWO_USER_ID).isDefined) {
      Future.successful(Results.Redirect("/logout"))
    } else if (tarpit.contains(ip)) {
      throwThemInAPit(nextFilter(requestHeader))
    } else {
      nextFilter(requestHeader)
    }
  }

  val blocked = Seq(
    "103.60.176.6",
    "103.60.176.238",
    "43.249.225.14",
    "95.5.131.183",
    "88.251.246.206",
    "88.251.181.56"
  )

  val tarpit = Seq()

  private def throwThemInAPit[T](andThen: => Future[T]) = {
    play.api.Play.maybeApplication.collect {
      case app if app.mode != play.api.Mode.Test =>
        import scala.concurrent.duration._
        import scala.concurrent.ExecutionContext.Implicits.global
        val promise = Promise[Unit]()
        val delay = (util.Random.nextInt(10) + 4).seconds // 4 to 14 seconds
        play.libs.Akka.system.scheduler.scheduleOnce(delay) { promise.success((): Unit) }
        promise.future.flatMap { _ => andThen }
    }.getOrElse(andThen)
  }
}
