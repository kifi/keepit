package com.keepit.common.controller

import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.logging.AccessLog
import com.keepit.common.strings.UTF8
import com.keepit.common.logging.Access._
import com.keepit.FortyTwoGlobal
import com.keepit.common.zookeeper.ServiceDiscovery

import play.api.mvc._
import play.api.Play
import play.mvc.Http.Status
import play.api.libs.iteratee.{Done, Iteratee, Enumerator}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.amazon.MyAmazonInstanceInfo

class LoggingFilter() extends EssentialFilter {

  lazy val global = Play.current.global.asInstanceOf[FortyTwoGlobal]
  lazy val accessLog = global.injector.instance[AccessLog]
  lazy val discovery = global.injector.instance[ServiceDiscovery]
  lazy val airbrake = global.injector.instance[AirbrakeNotifier]
  lazy val myAmazonInstanceInfo = global.injector.instance[MyAmazonInstanceInfo]
  lazy val midFlightRequests = global.injector.instance[MidFlightRequests]

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader): Iteratee[Array[Byte], SimpleResult] = {
      if (!discovery.amIUp && (discovery.timeSinceLastStatusChange > 20000L)) {
        val message = s"Current status of service ${myAmazonInstanceInfo.info.localIp} ${discovery.myStatus.getOrElse("UNKNOWN")}, last changed ${discovery.timeSinceLastStatusChange}ms ago"
        //system is going down, maybe the logger, emails, airbrake are gone already
        println(message)
        //we can remove this airbrake once we'll see the system works right
        airbrake.notify(message)
        Done(SimpleResult(header = ResponseHeader(Status.SERVICE_UNAVAILABLE), body = Enumerator()))
      } else {
        val countStart = midFlightRequests.comingIn(rh)
        val timer = accessLog.timer(HTTP_IN)

        def logTime(result: SimpleResult): SimpleResult = {
          midFlightRequests.goingOut(rh)
          val trackingId = rh.headers.get(CommonHeaders.TrackingId).getOrElse(null)
          val remoteServiceId = rh.headers.get(CommonHeaders.LocalServiceId).getOrElse(null)
          val remoteIsLeader = rh.headers.get(CommonHeaders.IsLeader).getOrElse(null)
          val remoteServiceType = rh.headers.get(CommonHeaders.LocalServiceType).getOrElse(null)

          //report headers and query string only if there was an error
          val duration: Long = if (result.header.status / 100 >= 4) {
            result.body(Iteratee.head[Array[Byte]]).map { fut =>
              fut.map { arrayOpt =>
                val body = arrayOpt.map { array => new String(array.take(512), UTF8) } getOrElse null //null is usually bad, but fits the timer api
                accessLog.add(timer.done(
                  trackingId = trackingId,
                  remoteLeader = remoteIsLeader,
                  remoteServiceId = remoteServiceId,
                  remoteServiceType = remoteServiceType,
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
              method = rh.method,
              currentRequestCount = countStart,
              url = rh.uri,
              statusCode = result.header.status
            )).duration
          }
          val headers = (CommonHeaders.ResponseTime -> duration.toString) ::
                        (CommonHeaders.IsUP -> (if(discovery.amIUp) "Y" else "N")) ::
                        (CommonHeaders.LocalServiceId -> discovery.thisInstance.map(_.id.id.toString).getOrElse("NA")) ::
                        Nil
          val headersWithCount = if (duration > 30) {
            (CommonHeaders.MidFlightRequestCount -> midFlightRequests.count.toString) :: headers
          } else {
            headers
          }
          result.withHeaders(headersWithCount: _*)
        }
        try {
          next(rh).map {
            case plain: SimpleResult => logTime(plain)
          }
        } catch {
          case t: Throwable =>
            midFlightRequests.goingOut(rh)
            throw t
        }
      }
    }
  }
}
