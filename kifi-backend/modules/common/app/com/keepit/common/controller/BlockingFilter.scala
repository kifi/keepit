package com.keepit.common.controller

import com.keepit.common.service.IpAddress

import scala.concurrent.Promise
import play.api.mvc.{ Results, Result, RequestHeader, Filter }

import scala.concurrent.Future

object BlockingFilter extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val ip = IpAddress.fromRequest(requestHeader)
    if (blocked.exists(b => ip.ip.startsWith(b)) && !requestHeader.path.contains("logout") && requestHeader.session.get(KifiSession.FORTYTWO_USER_ID).isDefined) {
      Future.successful(Results.Redirect("/logout"))
    } else if (tarpit.contains(ip)) {
      throwThemInAPit(nextFilter(requestHeader))
    } else {
      nextFilter(requestHeader)
    }
  }

  val blocked = Seq(
    "14.141.175.",
    "27.255.",
    "43.249.225.",
    "49.201.2.",
    "88.250.172",
    "88.251.",
    "95.5.131.183",
    "103.60.176.",
    "103.240.169.",
    "110.55.2.",
    "110.55.4.",
    "112.133.244.",
    "112.196.92.",
    "115.111.183.",
    "117.248.",
    "117.205.",
    "122.173.134.",
    "124.125.152.",
    "124.253.",
    "125.62.114.",
    "150.129.198.",
    "180.87.245.",
    "182.69.9.",
    "182.72.136.",
    "202.131.107."
  )

  val tarpit = Seq(
    "12.47.130.201", // Declara
    "24.6.100.185", // edcast
    "173.13.190.50" // edcast
  )

  private def throwThemInAPit[T](andThen: => Future[T]) = {
    play.api.Play.maybeApplication.collect {
      case app if app.mode != play.api.Mode.Test =>
        import scala.concurrent.duration._
        import scala.concurrent.ExecutionContext.Implicits.global
        val promise = Promise[Unit]()
        val delay = (util.Random.nextInt(5) + 1).seconds // 1 to 6 seconds
        play.libs.Akka.system.scheduler.scheduleOnce(delay) { promise.success((): Unit) }
        promise.future.flatMap { _ => andThen }
    }.getOrElse(andThen)
  }
}
