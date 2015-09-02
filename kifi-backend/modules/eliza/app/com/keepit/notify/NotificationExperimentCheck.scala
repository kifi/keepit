package com.keepit.notify

import com.google.inject.{ Singleton, Inject }
import com.keepit.model.UserExperimentType
import com.keepit.notify.model.{ EmailRecipient, Recipient, UserRecipient }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.{ Mode, Play }

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * A service to temporarily restrict the new notification system to only those with the user experiment.
 * The results are cached in the user recipient object for later checks.
 */
@Singleton
class NotificationExperimentCheck @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient) {

  def checkExperiment(recipient: Recipient): NotificationExperimentCheck.Result = {
    // Don't want to have to keep switching experiments in dev mode
    if (Play.maybeApplication.exists(_.mode == Mode.Dev)) {
      NotificationExperimentCheck.Result(true, recipient match {
        case u: UserRecipient => u.copy(experimentEnabled = Some(true))
        case other => other
      })
    } else {
      recipient match {
        case u @ UserRecipient(id, experimentEnabled) => experimentEnabled match {
          case None =>
            val experiments = Await.result(shoeboxServiceClient.getUserExperiments(id), 10 seconds)
            val enabled = experiments.contains(UserExperimentType.NEW_NOTIFS_SYSTEM)
            NotificationExperimentCheck.Result(enabled, u.copy(experimentEnabled = Some(enabled)))
          case Some(result) => NotificationExperimentCheck.Result(result, u)
        }
        case _: EmailRecipient => NotificationExperimentCheck.Result(false, recipient)
      }
    }
  }

  def ifExperiment(recipient: Recipient)(f: (Recipient) => Unit): Recipient = {
    val check = checkExperiment(recipient)
    if (check.experimentEnabled) {
      f(check.recipient)
    }
    check.recipient
  }

  def ifElseExperiment(recipient: Recipient)(f: (Recipient) => Unit)(elseF: (Recipient) => Unit): Recipient = {
    val check = checkExperiment(recipient)
    if (check.experimentEnabled) {
      f(check.recipient)
    } else {
      elseF(check.recipient)
    }
    check.recipient
  }

}

object NotificationExperimentCheck {

  case class Result(experimentEnabled: Boolean, recipient: Recipient)

}
