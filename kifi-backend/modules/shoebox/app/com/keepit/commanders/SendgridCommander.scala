package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ ElectronicMail, ElectronicMailRepo, EmailAddress, SystemEmailAddress }
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.heimdal.{ HeimdalContextBuilderFactory, HeimdalServiceClient, NonUserEvent, NonUserEventTypes, UserEvent, UserEventTypes }
import com.keepit.model.{ UriRecommendationFeedback, EmailOptOutRepo, NotificationCategory, UserEmailAddressRepo, UserEmailAddressStates }
import com.keepit.social.NonUserKinds
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class SendgridCommander @Inject() (
    db: Database,
    systemAdminMailSender: SystemAdminMailSender,
    heimdalClient: HeimdalServiceClient,
    emailAddressRepo: UserEmailAddressRepo,
    electronicMailRepo: ElectronicMailRepo,
    emailOptOutRepo: EmailOptOutRepo,
    recoCommander: RecommendationsCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends Logging {

  import SendgridEventTypes._

  val alertEvents: Seq[SendgridEventType] = Seq(BOUNCE, SPAM_REPORT)
  val unsubscribeEvents: Seq[SendgridEventType] = Seq(UNSUBSCRIBE, BOUNCE)

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
        contextBuilder += ("action", eventType.name)
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
    val eventName: Option[SendgridEventType] = event.event

    val emailOpt = for {
      mailId <- event.mailId
      mail <- db.readOnlyReplica { implicit s => electronicMailRepo.getOpt(mailId) }(captureLocation)
    } yield mail

    eventName.filter(alertEvents contains _).foreach(_ => emailAlert(event, emailOpt))
    eventName.filter(unsubscribeEvents contains _).foreach(_ => handleUnsubscribeEvent(event, emailOpt))

    eventName.filter(CLICK == _).foreach { _ =>
      emailOpt.foreach { email =>
        verifyEmailAddress(event, email)
        if (NotificationCategory.fromElectronicMailCategory(email.category) == NotificationCategory.User.DIGEST) {
          recordClickForDigestEmail(event, email)
        }
      }
    }

    sendHeimdalEvent(event, emailOpt)
  }

  private def clicked(url: String): String = url.toLowerCase match {
    case kifi if kifi.contains("kifi.com") => url
    case facebook if facebook.contains("facebook.com/pages/kifi") => "Kifi Facebook Page"
    case twitter if twitter.contains("twitter.com/kifi") => "Kifi Twitter Page"
    case linkedin if linkedin.contains("linkedin.com/company/fortytwo") => "Kifi LinkedIn Page"
    case _ => "External Page"
  }

  private def verifyEmailAddress(event: SendgridEvent, email: ElectronicMail): Unit =
    db.readWrite { implicit rw =>
      for {
        userEmail <- email.to.headOption
        emailAddr <- emailAddressRepo.getByAddressOpt(userEmail)
        if !emailAddr.verified
      } yield {
        log.info(s"verifying email(${userEmail}) from SendGrid event(${event})")

        emailAddressRepo.save(emailAddr.copy(state = UserEmailAddressStates.VERIFIED,
          verifiedAt = Some(currentDateTime)))
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

  private def recordClickForDigestEmail(event: SendgridEvent, email: ElectronicMail): Unit = {
    log.info(s"recordClickForDigestEmail(${event}, ${email})") // TODO (josh) remove this after debugging
    if (event.url.isEmpty || email.senderUserId.isEmpty) {
      log.warn(s"cannot record click event for digest email; url=${event.url} email=${email.senderUserId}")
      return
    }

    val userId = email.senderUserId.get
    val keepUrl = event.url.get
    val uriRecoFeedback = UriRecommendationFeedback(clicked = Some(true), kept = None)

    recoCommander.updateUriRecommendationFeedback(userId, keepUrl, uriRecoFeedback).map { ok =>
      if (!ok) log.warn(s"updateUriRecommendationFeedback($userId, $keepUrl, $uriRecoFeedback) returned false")
    }
  }
}

