package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.mail.{LocalPostOffice, PostOffice, EmailAddresses, ElectronicMail, ElectronicMailRepo}
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.{UserEventTypes, HeimdalContext, UserEvent, HeimdalServiceClient, HeimdalContextBuilderFactory}
import com.keepit.model.{EmailAddress, EmailAddressRepo}

class SendgridCommander @Inject() (
  db: Database,
  postOffice: LocalPostOffice,
  heimdalClient: HeimdalServiceClient,
  emailAddressRepo: EmailAddressRepo,
  electronicMailRepo: ElectronicMailRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory
  ) {

  def processNewEvents(events: Seq[SendgridEvent]): Unit = {
    events foreach { event =>
      emailAlert(event)
      report(event)
    }
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
    event.event foreach { eventType =>
      event.email foreach { address =>
        event.mailId foreach { mailId =>
        val emailOpt = db.readOnly{ implicit s =>
          electronicMailRepo.getOpt(mailId).toSet
        }
        for {
          email <- emailOpt if (PostOffice.Categories.User.all.contains(email.category))
          emailAddress <- db.readOnly{ implicit s => emailAddressRepo.getByAddress(address).toSet }
        } yield {
            val contextBuilder =  heimdalContextBuilder()
            contextBuilder += ("action", eventType)
            contextBuilder.addEmailInfo(email)
            val userEvent = UserEvent(emailAddress.userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED)
            heimdalClient.trackEvent(userEvent)
          }
        }
      }
    }
  }
}