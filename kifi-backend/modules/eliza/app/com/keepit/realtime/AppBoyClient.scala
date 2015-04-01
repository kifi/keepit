package com.keepit.realtime

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.common.time.Clock
import play.api.libs.json.JsObject

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[AppBoyClientImpl])
trait AppBoyClient {
  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse]
}

class AppBoyClientImpl @Inject() (
    clock: Clock,
    client: HttpClient,
    airbrake: AirbrakeNotifier,
    db: Database,
    deviceRepo: DeviceRepo,
    implicit val executionContext: ExecutionContext) extends AppBoyClient with Logging {

  lazy val httpClient: HttpClient = {
    client.withHeaders("Content-Type" -> "application/json")
      .withTimeout(CallTimeouts(maxWaitTime = Some(10000), responseTimeout = Some(10000), maxJsonParseTime = Some(1000)))
  }

  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse] = {
    post(json, device, notification)
  }

  protected def post(json: JsObject, device: Device, notification: PushNotification, trial: Int = 1): Future[ClientResponse] = {
    val url = s"${AppBoyConfig.baseUrl}/messages/send"
    log.info(s"[AppBoyClient] POST request to $url with body: $json, with device: $device, with notif: $notification: trial: $trial")
    httpClient.postFuture(DirectUrl(url), json,
      { req =>
        {
          case t: Throwable =>
            throw new Exception(s"[stop trying] error posting to appboy json $json on device $device notification $notification on trial $trial, not attempting more retries", t)
        }
      })
  }

}
