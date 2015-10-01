package com.keepit.realtime

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.logging.Logging
import com.keepit.eliza._
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
case class MessageCountPushNotification(unvisitedCount: Int) extends PushNotification {
  val sound = None
  val message = None
}
case class SimplePushNotification(unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound] = None, category: SimplePushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification
case class LibraryUpdatePushNotification(unvisitedCount: Int, message: Option[String], libraryId: Id[Library], libraryUrl: String, sound: Option[NotificationSound] = None, category: LibraryPushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification
case class UserPushNotification(unvisitedCount: Int, message: Option[String], userExtId: ExternalId[User], pictureUrl: String, username: Username, sound: Option[NotificationSound] = None, category: UserPushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification
case class OrgPushNotification(unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound] = None, category: OrgPushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification

@json case class NotificationSound(name: String)

object MobilePushNotifier {
  val DefaultNotificationSound = NotificationSound("notification.aiff")
  val MoreMessageNotificationSound = NotificationSound("newnotificationoutsidemessage.aiff")
  val RecheckPeriod = Days.THREE
}

@ImplementedBy(classOf[MobilePushDelegator])
trait MobilePushNotifier {
  def registerDevice(userId: Id[User], token: Option[String], deviceType: DeviceType, isDev: Boolean, signature: Option[String]): Either[String, Device]
  def notifyUser(userId: Id[User], notification: PushNotification, force: Boolean): Future[Int]
}

class MobilePushDelegator @Inject() (
    appBoy: AppBoy,
    db: Database,
    deviceRepo: DeviceRepo,
    implicit val executionContext: ExecutionContext) extends MobilePushNotifier with Logging {

  def registerDevice(userId: Id[User], token: Option[String], deviceType: DeviceType, isDev: Boolean, signature: Option[String]): Either[String, Device] = {
    token match {
      case Some(tokenStr) =>
        log.warn(s"[MobilePush] $userId tried to register UrbanAirship device. $token, $deviceType $isDev $signature")
        Left("unsupported_platform")
      case None if signature.isDefined => Right(appBoy.registerDevice(userId, deviceType, isDev, signature.get))
      case _ => Left("invalid_params")
    }
  }

  def notifyUser(userId: Id[User], notification: PushNotification, force: Boolean): Future[Int] = {
    val (devicesWithToken, devicesNoToken) = db.readOnlyMaster { implicit s =>
      deviceRepo.getByUserId(userId, None)
    }.partition(d => d.token.isDefined)

    val numSentAppBoyF = if (devicesNoToken.nonEmpty) {
      appBoy.notifyUser(userId, devicesNoToken, notification, force)
    } else Future.successful(0)

    for {
      numSentAppBoy <- numSentAppBoyF
    } yield {
      numSentAppBoy
    }
  }

}
