package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.mail.{LocalPostOffice, PostOffice, EmailAddresses, ElectronicMail}
import com.keepit.common.db.slick.Database

class SendgridCommander @Inject() (
  db: Database,
  postOffice: LocalPostOffice
  ) {

  def processNewEvents(events: Seq[SendgridEvent]): Unit = {
    events foreach emailAlert
  }

  private val alertEventTypes = Set("dropped", "bounce", "spamreport")

  private def emailAlert(event: SendgridEvent): Unit = {
    event.event foreach { eventType =>
      if (alertEventTypes.contains(eventType)) {
        db.readWrite{ implicit s =>
          postOffice.sendMail(
            ElectronicMail(
              from = EmailAddresses.ENG,
              to = List(EmailAddresses.EISHAY),
              subject = s"Sendgrid event [$eventType]",
              htmlBody = s"Got event:<br/>\n $event",
              category = PostOffice.Categories.System.ADMIN))
        }
      }
    }
  }

  private def report(event: SendgridEvent): Unit = {
    // todo(Martin): send a user event to Heimdal of type WAS_NOTIFIED, action event.event, channel email cf. MessagingAnalytics.clearedNotification
  }
}
