package com.keepit.realtime

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ ClientResponse, CallTimeouts, NonOKResponseException, DirectUrl, HttpClient }
import com.keepit.common.time._
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.JsObject

import scala.concurrent.Future

@ImplementedBy(classOf[DevAndProdUrbanAirshipClient])
trait UrbanAirshipClient {
  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse]
  def updateDeviceState(device: Device): Unit
}

abstract class UrbanAirshipClientImpl(clock: Clock, config: UrbanAirshipConfig, airbrake: AirbrakeNotifier, db: Database, deviceRepo: DeviceRepo) extends UrbanAirshipClient with Logging {
  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse]

  def httpClient: HttpClient

  private def validate(json: JsObject, device: Device, notification: PushNotification, validationId: ExternalId[Nothing]): Unit = {
    val res = httpClient.postFuture(DirectUrl(s"${config.baseUrl}/api/push/validate"), json)
    res.onFailure {
      case bad =>
        log.error(s"[$validationId] json ${json.toString()} for $notification to $device did not validate: ${bad.toString}", bad)
        airbrake.notify(s"[$validationId] json ${json.toString()} for $notification to $device did not validate: ${bad.toString}", bad)
    }
    res.onSuccess {
      case good =>
        log.info(s"[$validationId] json for $notification to $device did validate: ${good.body}")
    }
  }

  protected def post(json: JsObject, device: Device, notification: PushNotification, trial: Int = 1): Future[ClientResponse] = {
    httpClient.postFuture(DirectUrl(s"${config.baseUrl}/api/push"), json,
      { req =>
        {
          case t: Throwable =>
            throw new Exception(s"[stop trying] error posting to urbanairship json $json on device $device notification $notification on trial $trial, not attempting more retries", t)
        }
      }
    )
  }

  def updateDeviceState(device: Device): Unit = {
    log.info(s"Checking state of device: ${device.token}")
    if (device.updatedAt plus UrbanAirship.RecheckPeriod isBefore clock.now()) {
      val uaUrl = if (device.isChannel) {
        log.info(s"device $device is using a channel")
        s"${config.baseUrl}/api/channels/${device.token}"
      } else {
        device.deviceType match {
          case DeviceType.IOS => s"${config.baseUrl}/api/device_tokens/${device.token}"
          case DeviceType.Android => s"${config.baseUrl}/api/apids/${device.token}"
          case dt => throw new Exception(s"Unknown device type: $dt")
        }
      }
      httpClient.getFuture(DirectUrl(uaUrl), req => {
        case e @ NonOKResponseException(req, response, _) if response.status == NOT_FOUND =>
        case e: Exception =>
          val fullException = req.tracer.withCause(e)
          airbrake.notify(
            AirbrakeError.outgoing(
              exception = fullException,
              request = req.req,
              message = s"UrbanAirship Client calling ${req.httpUri.summary} for update device $device state failed"
            )
          )
          //there was a problem with UrbanAirship getting updates for this device.
          //touching the device update timestamp so we won't try it again in the near future, hope that they recover by next time we'll try it.
          db.readWrite { implicit s => deviceRepo.save(device) }
      }) map { r =>
        val json = r.json
        log.info(s"device report for $device: $json")
        val active = (json \ "active").as[Boolean]
        db.readWrite { implicit s =>
          val state = if (active) DeviceStates.ACTIVE else DeviceStates.INACTIVE
          log.info(s"Setting device state to $state: ${device.token}")
          deviceRepo.save(device.copy(state = state))
        }
      } recover {
        case e @ NonOKResponseException(url, response, _) if response.status == NOT_FOUND =>
          db.readWrite { implicit s =>
            log.info(s"Setting device state to inactive: ${device.token}")
            deviceRepo.save(device.copy(state = DeviceStates.INACTIVE))
          }
      }
    }
  }

}

class DevAndProdUrbanAirshipClient @Inject() (
    prod: ProdUrbanAirshipClient,
    dev: DevUrbanAirshipClient) extends UrbanAirshipClient {

  def updateDeviceState(device: Device): Unit = {
    if (device.isDev) {
      dev.updateDeviceState(device)
    } else {
      prod.updateDeviceState(device)
    }
  }

  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse] = {
    if (device.isDev) {
      dev.send(json, device, notification)
    } else {
      prod.send(json, device, notification)
    }
  }

}

class ProdUrbanAirshipClient @Inject() (
    client: HttpClient,
    clock: Clock, config: UrbanAirshipConfig, airbrake: AirbrakeNotifier, db: Database, deviceRepo: DeviceRepo) extends UrbanAirshipClientImpl(clock, config, airbrake, db, deviceRepo) {

  lazy val httpClient: HttpClient = {
    val encodedUserPass = new sun.misc.BASE64Encoder().encode(s"${config.key}:${config.secret}".getBytes)
    client.withHeaders("Authorization" -> s"Basic $encodedUserPass")
      .withHeaders("Content-Type" -> "application/json")
      .withHeaders("Accept" -> "application/vnd.urbanairship+json; version=3;")
      .withTimeout(CallTimeouts(maxWaitTime = Some(10000), responseTimeout = Some(10000), maxJsonParseTime = Some(1000)))
  }

  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse] = {
    if (device.isDev) throw new Exception(s"Not supporting dev device: $device")
    post(json, device, notification)
  }
}

class DevUrbanAirshipClient @Inject() (
    client: HttpClient,
    clock: Clock, config: UrbanAirshipConfig, airbrake: AirbrakeNotifier, db: Database, deviceRepo: DeviceRepo) extends UrbanAirshipClientImpl(clock, config, airbrake, db, deviceRepo) {

  lazy val httpClient: HttpClient = {
    val encodedUserPass = new sun.misc.BASE64Encoder().encode(s"${config.devKey}:${config.devSecret}".getBytes)
    client.withHeaders("Authorization" -> s"Basic $encodedUserPass")
      .withHeaders("Content-Type" -> "application/json")
      .withHeaders("Accept" -> "application/vnd.urbanairship+json; version=3;")
      .withTimeout(CallTimeouts(maxWaitTime = Some(10000), responseTimeout = Some(10000), maxJsonParseTime = Some(1000)))
  }

  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse] = {
    if (!device.isDev) throw new Exception(s"Not supporting prod device: $device")
    post(json, device, notification)
  }
}
