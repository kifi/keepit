package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.mail.{EmailAddress, SystemEmailAddress, ElectronicMail, ElectronicMailRepo}
import com.keepit.common.db.slick.Database
import com.keepit.heimdal._
import com.keepit.common.performance.timing
import com.keepit.model.{NotificationCategory, UserEmailAddressRepo}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.social.NonUserKinds
import scala.util.Try

class SendgridCommander @Inject() (
  db: Database,
  systemAdminMailSender: SystemAdminMailSender,
  heimdalClient: HeimdalServiceClient,
  emailAddressRepo: UserEmailAddressRepo,
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
        systemAdminMailSender.sendMail(
          ElectronicMail(
            from = SystemEmailAddress.ENG,
            to = List(SystemEmailAddress.SUPPORT, SystemEmailAddress.SENDGRID),
            subject = s"Sendgrid event [$eventType]",
            htmlBody = htmlBody,
            category = NotificationCategory.System.ADMIN))
      }
    }
  }

  private def sendHeimdalEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit =
    timing(s"sendgrid heimdalEvent eventType(${event.event}}) mailId(${event.mailId}}) ") {
    for {
      eventType <- event.event
      rawAddress <- event.email
      address <- EmailAddress.validate(rawAddress).toOption
      email <- emailOpt
    } yield {

      lazy val context = {
        val contextBuilder =  heimdalContextBuilder()
        contextBuilder += ("action", eventType)
        event.url.foreach { url => contextBuilder += ("clicked", clicked(url)) }
        contextBuilder.addEmailInfo(email)
        contextBuilder.build
      }

      val relevantUsers = if (NotificationCategory.User.all.contains(email.category)) {
        db.readOnly{ implicit s => emailAddressRepo.getByAddress(address).map(_.userId).toSet }(Database.Slave, captureLocation)
      } else Set.empty

      if (relevantUsers.nonEmpty) relevantUsers.foreach { userId =>
        heimdalClient.trackEvent(UserEvent(userId, context, UserEventTypes.WAS_NOTIFIED, event.timestamp))
      } else if (NotificationCategory.NonUser.all.contains(email.category)) {
        heimdalClient.trackEvent(NonUserEvent(address.address, NonUserKinds.email, context, NonUserEventTypes.WAS_NOTIFIED, event.timestamp))
      }
    }
  }

  private def report(event: SendgridEvent): Unit = {
    val emailOpt = timing(s"sendgrid (fetch email for alert) eventType(${event.event}}) mailId(${event.mailId}}) ") {
      for {
        mailId <- event.mailId
        mail <- db.readOnly{ implicit s => electronicMailRepo.getOpt(mailId) }(Database.Slave, captureLocation)
      } yield mail
    }
    emailAlert(event, emailOpt)
    sendHeimdalEvent(event, emailOpt)
  }

  private def clicked(url: String): String = url.toLowerCase match {
    case kifi if kifi.contains("kifi.com") => url
    case facebook if facebook.contains("facebook.com/pages/kifi") => "Kifi Facebook Page"
    case twitter if twitter.contains("twitter.com/kifi") => "Kifi Twitter Page"
    case linkedin if linkedin.contains("linkedin.com/company/fortytwo") => "Kifi LinkedIn Page"
    case _ => "External Page"
  }
}
