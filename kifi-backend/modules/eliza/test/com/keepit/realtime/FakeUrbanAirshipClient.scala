package com.keepit.realtime

import com.keepit.common.net.{ ClientResponse, DirectUrl, FakeClientResponse }
import play.api.libs.json.JsObject

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class FakeUrbanAirshipClient extends UrbanAirshipClient {

  val jsons = ArrayBuffer[JsObject]()

  def send(json: JsObject, device: Device, notification: PushNotification): Future[ClientResponse] = {
    jsons.append(json)
    Future.successful(FakeClientResponse.emptyFakeHttpClient(DirectUrl("/")))
  }

  def updateDeviceState(device: Device): Unit = {}
}
