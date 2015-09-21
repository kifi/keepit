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

  def checkUserExperiment(recipient: Recipient): LegacyNotificationCheck.Result = {
    Await.result(checkUserExperimentAsync(recipient), 10 seconds)
  }

  def checkUserExperimentAsync(recipient: Recipient): Future[LegacyNotificationCheck.Result] = {
    // Don't want to have to keep switching experiments in dev mode
    val flipResults = Play.maybeApplication.exists(_.mode == Mode.Dev)
    recipient match {
      case u @ UserRecipient(id, experimentEnabled) => experimentEnabled match {
        case None =>
          Future.successful(LegacyNotificationCheck.Result(true, u.copy(experimentEnabled = Some(true))))
        case Some(result) => Future.successful(LegacyNotificationCheck.Result(result, u))
      }
      case _: EmailRecipient => Future.successful(LegacyNotificationCheck.Result(false, recipient))
    }
  }

  def ifUserExperiment(recipient: Recipient)(f: (Recipient) => Unit): Recipient = {
    val check = checkUserExperiment(recipient)
    if (check.experimentEnabled) {
      f(check.recipient)
    }
    check.recipient
  }

  def ifElseUserExperiment(recipient: Recipient)(f: (Recipient) => Unit)(elseF: (Recipient) => Unit): Recipient = {
    val check = checkUserExperiment(recipient)
    if (check.experimentEnabled) {
      f(check.recipient)
    } else {
      elseF(check.recipient)
    }
    check.recipient
  }

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
