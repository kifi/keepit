package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.performance.timing
import com.keepit.common.time.Clock
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.social.NonUserKinds

object SendgridEventTypes {
  val CLICK = "click"
  val UNSUBSCRIBE = "unsubscribe"
  val BOUNCE = "bounce"
  val SPAM_REPORT = "spamreport"
}

class SendgridCommander @Inject() (
    db: Database,
    clock: Clock,
    systemAdminMailSender: SystemAdminMailSender,
    heimdalClient: HeimdalServiceClient,
    emailAddressRepo: UserEmailAddressRepo,
    electronicMailRepo: ElectronicMailRepo,
    emailOptOutRepo: EmailOptOutRepo,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends Logging {

  import com.keepit.commanders.SendgridEventTypes._

  val alertEvents: Seq[String] = Seq(BOUNCE, SPAM_REPORT)
  val unsubscribeEvents: Seq[String] = Seq(UNSUBSCRIBE, BOUNCE)

  def processNewEvents(events: Seq[SendgridEvent]): Unit = {
    events foreach report
  }

  private def emailAlert(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit = {
    log.info(s"sendgrid emailAlert eventType(${event.event}}) mailId(${event.mailId}}) ")
    val htmlBody = emailOpt match {
      case Some(email) => s"""|Got event:<br/> $event<br/><br/>
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
        subject = s"Sendgrid event [${event.event}]",
        htmlBody = htmlBody,
        category = NotificationCategory.System.ADMIN))
  }

  private def sendHeimdalEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit = {
    log.info(s"sendgrid heimdalEvent eventType(${event.event}}) mailId(${event.mailId}}) ")
    for {
      eventType <- event.event
      rawAddress <- event.email
      address <- EmailAddress.validate(rawAddress).toOption
      email <- emailOpt
    } yield {

      lazy val context = {
        val contextBuilder = heimdalContextBuilder()
        contextBuilder += ("action", eventType)
        event.url.foreach { url => contextBuilder += ("clicked", clicked(url)) }
        contextBuilder.addEmailInfo(email)
        contextBuilder.build
      }

      val relevantUsers = if (NotificationCategory.User.all.contains(email.category)) {
        db.readOnlyReplica { implicit s => emailAddressRepo.getByAddress(address).map(_.userId).toSet }(captureLocation)
      } else Set.empty

      if (relevantUsers.nonEmpty) relevantUsers.foreach { userId =>
        heimdalClient.trackEvent(UserEvent(userId, context, UserEventTypes.WAS_NOTIFIED, event.timestamp))
      }
      else if (NotificationCategory.NonUser.all.contains(email.category)) {
        heimdalClient.trackEvent(NonUserEvent(address.address, NonUserKinds.email, context, NonUserEventTypes.WAS_NOTIFIED, event.timestamp))
      }
    }
  }

  private def report(event: SendgridEvent): Unit = {
    val eventName: Option[String] = event.event

    val emailOpt = for {
      mailId <- event.mailId
      mail <- db.readOnlyReplica { implicit s => electronicMailRepo.getOpt(mailId) }(captureLocation)
    } yield mail

    eventName.filter(alertEvents contains _).foreach(_ => emailAlert(event, emailOpt))
    eventName.filter(unsubscribeEvents contains _).foreach(_ => handleUnsubscribeEvent(event, emailOpt))
    eventName.filter(CLICK == _).foreach(_ => handleClickEvent(event, emailOpt))

    sendHeimdalEvent(event, emailOpt)
  }

  private def clicked(url: String): String = url.toLowerCase match {
    case kifi if kifi.contains("kifi.com") => url
    case facebook if facebook.contains("facebook.com/pages/kifi") => "Kifi Facebook Page"
    case twitter if twitter.contains("twitter.com/kifi") => "Kifi Twitter Page"
    case linkedin if linkedin.contains("linkedin.com/company/fortytwo") => "Kifi LinkedIn Page"
    case _ => "External Page"
  }

  private def handleClickEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit =
    db.readWrite { implicit rw =>
      for {
        email <- emailOpt
        userEmail <- email.to.headOption
        emailAddr <- emailAddressRepo.getByAddressOpt(userEmail)
        if !emailAddr.verified
      } yield {
        log.info(s"verifying email(${userEmail}) from SendGrid event(${event})")
        emailAddressRepo.save(emailAddr.copy(state = UserEmailAddressStates.VERIFIED, verifiedAt = Some(clock.now)))
      }
    }

  private def handleUnsubscribeEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit =
    db.readWrite { implicit rw =>
      for {
        email <- emailOpt
        userEmail <- email.to.headOption
      } yield {
        log.info(s"SendGrid unsubscribe email(${userEmail} from SendGrid event(${event})")
        emailOptOutRepo.optOut(userEmail, NotificationCategory.ALL)
      }
    }
}

