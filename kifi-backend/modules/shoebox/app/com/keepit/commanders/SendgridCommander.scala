package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.mail.{EmailAddresses, ElectronicMail, ElectronicMailRepo}
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.{UserEventTypes, UserEvent, HeimdalServiceClient, HeimdalContextBuilderFactory}
import com.keepit.common.performance.timing
import com.keepit.model.{NotificationCategory, EmailAddressRepo}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.SystemAdminMailSender

class SendgridCommander @Inject() (
  db: Database,
  systemAdminMailSender: SystemAdminMailSender,
  heimdalClient: HeimdalServiceClient,
  emailAddressRepo: EmailAddressRepo,
  electronicMailRepo: ElectronicMailRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory
) extends Logging {

  def processNewEvents(events: Seq[SendgridEvent]): Unit = {
    events foreach report
  }

  private val alertEventTypes = Set("bounce", "spamreport")

  private def emailAlert(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit =
    timing(s"sendgrid emailAlert eventType(${event.event}}) mailId(${event.mailId}}) ") {
    event.event foreach { eventType =>
      if (alertEventTypes.contains(eventType)) {
        val htmlBody = emailOpt match {
          case Some(email) =>  s"""|Got event:<br/> $event<br/><br/>
                                   |Email data:<br/>
                                   |Category: ${email.category}<br/>
                                   |Subject: ${email.subject}<br/>
                                   |From: ${email.from}<br/>
                                   |To: ${email.to}<br/>
                                   |CC: ${email.cc}<br/>
                                   |Created at: ${email.createdAt}<br/>
                                   |Updated at: ${email.updatedAt}<br/>""".stripMargin
          case None => s"Got event:<br/> $event"
        }
        db.readWrite{ implicit s =>
          systemAdminMailSender.sendMail(
            ElectronicMail(
              from = EmailAddresses.ENG,
              to = List(EmailAddresses.SUPPORT, EmailAddresses.SENDGRID),
              subject = s"Sendgrid event [$eventType]",
              htmlBody = htmlBody,
              category = NotificationCategory.System.ADMIN))
        }
      }
    }
  }

  private def heimdalEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit =
    timing(s"sendgrid heimdalEvent eventType(${event.event}}) mailId(${event.mailId}}) ") {
    for {
      eventType <- event.event
      address <- event.email
      email <- emailOpt
    } yield {
      if (NotificationCategory.User.all.contains(email.category)) {
        val emailAddresses = db.readOnly{ implicit s => emailAddressRepo.getByAddress(address).toSet }(Database.Slave)
        emailAddresses foreach { emailAddress =>
          val contextBuilder =  heimdalContextBuilder()
          contextBuilder += ("action", eventType)
          contextBuilder.addEmailInfo(email)
          val userEvent = UserEvent(emailAddress.userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED)
          heimdalClient.trackEvent(userEvent)
        }
      }
    }
  }

  private def report(event: SendgridEvent): Unit = {
    val emailOpt = timing(s"sendgrid (fetch email for alert) eventType(${event.event}}) mailId(${event.mailId}}) ") {
      for {
        mailId <- event.mailId
        mail <- db.readOnly{ implicit s => electronicMailRepo.getOpt(mailId) }(Database.Slave)
      } yield mail
    }
    emailAlert(event, emailOpt)
    heimdalEvent(event, emailOpt)
  }
}