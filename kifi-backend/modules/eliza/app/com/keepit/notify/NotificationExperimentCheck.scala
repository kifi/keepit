package com.keepit.notify

import com.google.inject.{ Singleton, Inject }
import com.keepit.model.UserExperimentType
import com.keepit.notify.model.{ EmailRecipient, Recipient, UserRecipient }
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * A service to temporarily restrict the new notification system to only those with the user experiment.
 * The results are cached in the user recipient object for later checks.
 */
@Singleton
class NotificationExperimentCheck @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient) {

  def checkExperiment(recipient: Recipient): (Boolean, Recipient) = recipient match {

    case u @ UserRecipient(id, experimentEnabled) => experimentEnabled match {

      case None =>
        val experiments = Await.result(shoeboxServiceClient.getUserExperiments(id), 10 seconds)
        val enabled = experiments.contains(UserExperimentType.NEW_NOTIFS_SYSTEM)
        (enabled, u.copy(experimentEnabled = Some(enabled)))

      case Some(result) => (result, u)

    }

    case _: EmailRecipient => (false, recipient)

  }

  def ifExperiment(recipient: Recipient)(f: (Recipient) => Unit): Recipient = {
    val (experiment, newRecipient) = checkExperiment(recipient)
    if (experiment) {
      f(newRecipient)
    }
    newRecipient
  }

}
