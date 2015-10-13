package com.keepit.notify

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.{ NotificationItemRepo, NotificationRepo, NotificationItem, Notification }
import com.keepit.model.UserExperimentType
import com.keepit.notify.model.{ EmailRecipient, Recipient, UserRecipient }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.{ Mode, Play }

import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration._

/**
 * A service to temporarily restrict the new notification system to only those with the user experiment.
 * The results are cached in the user recipient object for later checks.
 */
@Singleton
class LegacyNotificationCheck @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    db: Database,
    notificationCommander: NotificationCommander,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    implicit val executionContext: ExecutionContext) {

  def ifNotifExists(potentialNotifId: String)(doIfExists: Notification => Unit)(doElse: => Unit): Unit = {
    val notifOpt = db.readOnlyMaster { implicit session =>
      ExternalId.asOpt[Notification](potentialNotifId).flatMap { id => notificationRepo.getOpt(id) }
    }
    notifOpt.fold(doElse) { notif => doIfExists(notif) }
  }

  def ifNotifExistsReturn[A](potentialNotifId: String)(doIfExists: Notification => A)(doElse: => A): A = {
    val notifOpt = db.readOnlyMaster { implicit session =>
      ExternalId.asOpt[Notification](potentialNotifId).flatMap { id => notificationRepo.getOpt(id) }
    }
    notifOpt.fold(doElse) { notif => doIfExists(notif) }
  }

  def ifNotifItemExists(potentialItemId: String)(doIfExists: (Notification, NotificationItem) => Unit)(doElse: => Unit): Unit = {
    val notifItemOpt = db.readOnlyMaster { implicit session =>
      ExternalId.asOpt[NotificationItem](potentialItemId).flatMap { id =>
        notificationItemRepo.getOpt(id)
      }.map { item =>
        (notificationRepo.get(item.notificationId), item)
      }
    }
    notifItemOpt.fold(doElse) {
      case (notif, item) => doIfExists(notif, item)
    }
  }

}

object LegacyNotificationCheck {

  case class Result(experimentEnabled: Boolean, recipient: Recipient)

}
