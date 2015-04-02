package com.keepit.realtime

import com.google.inject.{ ImplementedBy, Provider, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.logging.Logging
import com.keepit.eliza.{ PushNotificationExperiment, PushNotificationCategory }
import com.keepit.eliza.model.MessageThread
import com.keepit.model.{ Username, User, Library }
import com.kifi.macros.json
import org.joda.time.Days

import scala.concurrent.{ ExecutionContext, Future }

// Add fields to this object and handle them properly for each platform
sealed trait PushNotification {
  val unvisitedCount: Int
  val message: Option[String]
  val sound: Option[NotificationSound]
}

case class MessageThreadPushNotification(id: ExternalId[MessageThread], unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound]) extends PushNotification
case class SimplePushNotification(unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound] = None, category: PushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification
case class LibraryUpdatePushNotification(unvisitedCount: Int, message: Option[String], libraryId: Id[Library], libraryUrl: String, sound: Option[NotificationSound] = None, category: PushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification
case class UserPushNotification(unvisitedCount: Int, message: Option[String], userId: Id[User], pictureUrl: String, username: Username, sound: Option[NotificationSound] = None, category: PushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification

@json case class NotificationSound(name: String)

object MobilePushNotifier {
  val DefaultNotificationSound = NotificationSound("notification.aiff")
  val MoreMessageNotificationSound = NotificationSound("newnotificationoutsidemessage.aiff")
  val RecheckPeriod = Days.THREE
}

@ImplementedBy(classOf[MobilePushDelegator])
trait MobilePushNotifier {
  def registerDevice(userId: Id[User], token: Option[String], deviceType: DeviceType, isDev: Boolean, signature: Option[String]): Either[String, Device]
  def notifyUser(userId: Id[User], notification: PushNotification): Future[Int]
}

class MobilePushDelegator @Inject() (
    urbanAirship: Provider[UrbanAirshipImpl],
    appBoy: Provider[AppBoyImpl],
    db: Database,
    deviceRepo: DeviceRepo,
    implicit val executionContext: ExecutionContext) extends MobilePushNotifier with Logging {

  def registerDevice(userId: Id[User], token: Option[String], deviceType: DeviceType, isDev: Boolean, signature: Option[String]): Either[String, Device] = {
    token match {
      case Some(tokenStr) => Right(urbanAirship.get.registerDevice(userId, tokenStr, deviceType, isDev, signature))
      case None if signature.isDefined => Right(appBoy.get.registerDevice(userId, deviceType, isDev, signature.get))
      case _ => Left("invalid_params")
    }
  }

  def notifyUser(userId: Id[User], notification: PushNotification): Future[Int] = {
    val (devicesWithToken, devicesNoToken) = db.readOnlyMaster { implicit s =>
      deviceRepo.getByUserId(userId, None)
    }.partition(d => d.token.isDefined)

    val numSentUrbanAirshipF = urbanAirship.get.notifyUser(userId, devicesWithToken, notification)
    val numSentAppBoyF = appBoy.get.notifyUser(userId, devicesNoToken, notification)

    for {
      numSentUrbanAirship <- numSentUrbanAirshipF
      numSentAppBoy <- numSentAppBoyF
    } yield {
      numSentUrbanAirship + numSentAppBoy
    }
  }

}
