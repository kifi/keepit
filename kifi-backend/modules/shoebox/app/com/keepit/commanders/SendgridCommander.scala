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
    events foreach report
  }

  private val alertEventTypes = Set("dropped", "bounce", "spamreport")

  private def emailAlert(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit = {
    event.event foreach { eventType =>
      if (alertEventTypes.contains(eventType)) {
        db.readWrite{ implicit s =>
          val htmlBody = emailOpt match {
            case Some(email) =>  s"""|Got event:<br/> $event<br/><br/>
                                     |Email data:<br/>
                                     |Category: ${email.category}<br/>
                                     |Subject: ${email.subject}<br/>
                                     |Created at: ${email.createdAt}<br/>
                                     |Updated at: ${email.updatedAt}<br/>
                                     |Body:<br/> ${email.htmlBody}<br/>""".stripMargin
            case None => s"Got event:<br/> $event"
          }
          postOffice.sendMail(
            ElectronicMail(
              from = EmailAddresses.ENG,
              to = List(EmailAddresses.EISHAY),
              subject = s"Sendgrid event [$eventType]",
              htmlBody = htmlBody,
              category = PostOffice.Categories.System.ADMIN))
        }
      }
    }
  }

  private def heimdalEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit = {
    event.event foreach { eventType =>
      event.email foreach { address =>
        emailOpt foreach { email =>
          if (PostOffice.Categories.User.all.contains(email.category)) {
            val emailAddresses = db.readOnly{ implicit s => emailAddressRepo.getByAddress(address).toSet }
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
    }
  }

  private def report(event: SendgridEvent): Unit = {
    lazy val emailOpt = event.mailId map { mailId =>
      db.readOnly{ implicit s => electronicMailRepo.getOpt(mailId) }
    } getOrElse None
    emailAlert(event, emailOpt)
    heimdalEvent(event, emailOpt)
  }
}