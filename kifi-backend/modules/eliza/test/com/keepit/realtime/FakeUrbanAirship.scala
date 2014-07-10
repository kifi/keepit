package com.keepit.realtime

import scala.concurrent.{ Future, Promise }
import com.keepit.common.db.Id
import com.keepit.model.User
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Singleton, Inject, Provides }
import scala.collection.mutable.MutableList
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._

class FakeUrbanAirship extends UrbanAirship {
  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device = ???
  def notifyUser(userId: Id[User], notification: PushNotification): Unit = {}
  def sendNotification(firstMessage: Boolean, device: Device, notification: PushNotification): Unit = {}
  def updateDeviceState(device: Device): Future[Device] = Promise.successful(device).future
}

case class FakeUrbanAirshipModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  def fakeUrbanAirship(): UrbanAirship = {
    new FakeUrbanAirship()
  }
}

