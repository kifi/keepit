package com.keepit.realtime

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{CallTimeouts, NonOKResponseException, DirectUrl, HttpClient}
import com.keepit.common.time._
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.JsObject

import scala.concurrent.Future

@ImplementedBy(classOf[DevAndProdUrbanAirshipClient])
trait UrbanAirshipClient {
  def send(json: JsObject, device: Device, notification: PushNotification): Unit
  def updateDeviceState(device: Device): Future[Device]
}

abstract class UrbanAirshipClientImpl(clock: Clock, config: UrbanAirshipConfig, airbrake: AirbrakeNotifier, db: Database, deviceRepo: DeviceRepo) extends UrbanAirshipClient with Logging {
  def send(json: JsObject, device: Device, notification: PushNotification): Unit

  def httpClient: HttpClient

  private def validate(json: JsObject, device: Device, notification: PushNotification, validationId: ExternalId[Nothing]): Unit = {
    val res = httpClient.postFuture(DirectUrl(s"${config.baseUrl}/api/push/validate"), json)
    res.onFailure {
      case bad =>
        log.error(s"[AA] [$validationId] json ${json.toString()} for ${notification.id} to $device did not validate: ${bad.toString}", bad)
        airbrake.notify(s"[$validationId] json ${json.toString()} for ${notification.id} to $device did not validate: ${bad.toString}", bad)
    }
    res.onSuccess {
      case good =>
        log.info(s"[AA] [$validationId] json for ${notification.id} to $device did validate: ${good.body}")
    }
  }

  private val MaxTrials = 3

  protected def post(json: JsObject, device: Device, notification: PushNotification, trial: Int = 1): Unit = {
    val res = httpClient.postFuture(DirectUrl(s"${config.baseUrl}/api/push"), json,
      { req =>
        {
          case t: Throwable =>
            log.error(s"[AA] Error posting to urbanairship json $json on device $device notification $notification, trial #$trial - ${t.getMessage}", t)
            if (trial >= MaxTrials) {
              httpClient.defaultFailureHandler(req)
              val validationId = ExternalId()
              airbrake.notify(s"failed sending notification ${notification.id} to $device, checking validation with id $validationId", t)
              validate(json, device, notification, validationId)
              throw new Exception(s"[AA] [stop trying] error posting to urbanairship json $json on device $device notification $notification on trial $trial, not attempting more retries", t)
            }
            post(json, device, notification, trial + 1)
        }
      }
    )
    res.onSuccess {
      case clientRes =>
        if (clientRes.status != 200) {
          if (trial < MaxTrials) {
            log.info(s"[AA] failure to send notification $notification to device $device on trial $trial: ${clientRes.body}, trying more")
          } else {
            airbrake.notify(s"(on thread success) failure to send notification $notification to device $device: ${clientRes.body} trial $trial")
          }
        } else {
          log.info(s"[AA] successfully sent notification ${notification.id} to $device: ${clientRes.body}")
        }
    }
  }

  def updateDeviceState(device: Device): Future[Device] = {
    log.info(s"Checking state of device: ${device.token}")
    if (device.updatedAt plus UrbanAirship.RecheckPeriod isBefore clock.now()) {
      val uaUrl = device.deviceType match {
        case DeviceType.IOS => s"${config.baseUrl}/api/device_tokens/${device.token}"
        case DeviceType.Android => s"${config.baseUrl}/api/apids/${device.token}"
        case dt => throw new Exception(s"Unknown device type: $dt")
      }
      httpClient.getFuture(DirectUrl(uaUrl), url => {
        case e @ NonOKResponseException(url, response, _) if response.status == NOT_FOUND =>
      }) map { r =>
        val active = (r.json \ "active").as[Boolean]
        db.readWrite { implicit s =>
          val state = if (active) DeviceStates.ACTIVE else DeviceStates.INACTIVE
          log.info(s"[AA] Setting device state to $state: ${device.token}")
          deviceRepo.save(device.copy(state = state))
        }
      } recover {
        case e @ NonOKResponseException(url, response, _) if response.status == NOT_FOUND =>
          db.readWrite { implicit s =>
            log.info(s"[AA] Setting device state to inactive: ${device.token}")
            deviceRepo.save(device.copy(state = DeviceStates.INACTIVE))
          }
      }
    } else Future.successful(device)
  }

}

class DevAndProdUrbanAirshipClient @Inject() (
    prod: ProdUrbanAirshipClient,
    dev: DevUrbanAirshipClient) extends UrbanAirshipClient {

  def updateDeviceState(device: Device): Future[Device] = {
    if (device.isDev) {
      dev.updateDeviceState(device)
    } else {
      prod.updateDeviceState(device)
    }
  }

  def send(json: JsObject, device: Device, notification: PushNotification): Unit = {
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

  def send(json: JsObject, device: Device, notification: PushNotification): Unit = {
    if (device.isDev) throw new Exception(s"Not supporting dev device: $device")
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

  def send(json: JsObject, device: Device, notification: PushNotification): Unit = {
    if (!device.isDev) throw new Exception(s"Not supporting prod device: $device")
    post(json, device, notification)
  }
}
