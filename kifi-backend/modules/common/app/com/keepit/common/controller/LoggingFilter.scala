package com.keepit.common.controller

import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.logging.{ AccessLogTimer, AccessLog }
import com.keepit.common.strings._
import com.keepit.common.logging.Access._
import com.keepit.FortyTwoGlobal
import com.keepit.common.zookeeper.ServiceDiscovery

import play.api.mvc._
import play.api.Play
import play.mvc.Http.Status
import play.api.libs.iteratee.{ Done, Iteratee, Enumerator }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.amazon.MyInstanceInfo

class LoggingFilter() extends EssentialFilter {

  lazy val global = Play.current.global.asInstanceOf[FortyTwoGlobal]
  lazy val accessLog = global.injector.instance[AccessLog]
  lazy val discovery = global.injector.instance[ServiceDiscovery]
  lazy val airbrake = global.injector.instance[AirbrakeNotifier]
  lazy val myAmazonInstanceInfo = global.injector.instance[MyInstanceInfo]
  lazy val midFlightRequests = global.injector.instance[MidFlightRequests]

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader): Iteratee[Array[Byte], Result] = {
      if (!discovery.amIUp && (discovery.timeSinceLastStatusChange > 20000L)) {
        val message = s"Current status of service ${myAmazonInstanceInfo.info.localIp} ${discovery.myStatus.getOrElse("UNKNOWN")}, last changed ${discovery.timeSinceLastStatusChange}ms ago"
        //system is going down, maybe the logger, emails, airbrake are gone already
        println(message)
        //we can remove this airbrake once we'll see the system works right
        airbrake.notify(message)
        Done(Result(header = ResponseHeader(Status.SERVICE_UNAVAILABLE), body = Enumerator()))
      } else {
        val timer = accessLog.timer(HTTP_IN)
        var flightInfo: FlightInfo = EmptyFlightInfo
        val result = try {
          flightInfo = midFlightRequests.comingIn(rh)
          next(rh)
        } finally {
          midFlightRequests.goingOut(flightInfo)
        }
        result.map { case plain: Result => logTime(rh, plain, flightInfo.concurrentFlights, timer) }
      }
    }
  }

  private def logTime(rh: RequestHeader, result: Result, countStart: Int, timer: AccessLogTimer): Result = {
    val trackingId = rh.headers.get(CommonHeaders.TrackingId).getOrElse(null)
    val remoteServiceId = rh.headers.get(CommonHeaders.LocalServiceId).getOrElse(null)
    val remoteIsLeader = rh.headers.get(CommonHeaders.IsLeader).getOrElse(null)
    val remoteServiceType = rh.headers.get(CommonHeaders.LocalServiceType).getOrElse(null)
    //report headers and query string only if there was an error (4xx or 5xx)
    val duration: Long = if (result.header.status / 100 >= 4) {
      result.body(Iteratee.head[Array[Byte]]).map { fut =>
        fut.map { arrayOpt =>
          val body = arrayOpt.map { array =>
            //remember its only in error cases, the string is likely to be a very small description
            new String(array, UTF8).abbreviate(512)
          } getOrElse null //null is usually bad, but fits the timer api
          accessLog.add(timer.done(
            trackingId = trackingId,
            remoteLeader = remoteIsLeader,
            remoteServiceId = remoteServiceId,
            remoteServiceType = remoteServiceType,
            remoteAddress = rh.remoteAddress,
            remoteHeaders = rh.headers.toSimpleMap.mkString(","),
            query = rh.rawQueryString,
            method = rh.method,
            currentRequestCount = countStart,
            url = rh.uri,
            body = body,
            statusCode = result.header.status
          ))
        }
      }
      timer.laps
    } else {
      accessLog.add(timer.done(
        trackingId = trackingId,
        remoteLeader = remoteIsLeader,
        remoteServiceId = remoteServiceId,
        remoteServiceType = remoteServiceType,
        remoteAddress = rh.remoteAddress,
        method = rh.method,
        currentRequestCount = countStart,
        url = rh.uri,
        statusCode = result.header.status
      )).duration
    }
    val headers = (CommonHeaders.ResponseTime -> duration.toString) ::
      (CommonHeaders.IsUP -> (if (discovery.amIUp) "Y" else "N")) ::
      (CommonHeaders.LocalServiceId -> discovery.thisInstance.map(_.id.id.toString).getOrElse("NA")) ::
      Nil
    val headersWithCount = if (duration > 30) {
      (CommonHeaders.MidFlightRequestCount -> midFlightRequests.count.toString) :: headers
    } else {
      headers
    }
    result.withHeaders(headersWithCount: _*)
  }
}
