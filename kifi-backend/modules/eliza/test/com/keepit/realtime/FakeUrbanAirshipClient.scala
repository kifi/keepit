package com.keepit.realtime

import play.api.libs.json.JsObject

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class FakeUrbanAirshipClient extends UrbanAirshipClient {

  val jsons = ArrayBuffer[JsObject]()

  def send(json: JsObject, device: Device, notification: PushNotification): Unit = {
    jsons.append(json)
  }

  def updateDeviceState(device: Device): Future[Device] = ???
}
