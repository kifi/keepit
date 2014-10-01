package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress, EmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.NotificationCategory

import scala.concurrent.Future

class FeatureWaitlistEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  val emailTriggers = Map(
    "mobile_app" -> (views.html.email.black.mobileWaitlist, views.html.email.black.mobileWaitlistText)
  )

  def sendToUser(email: EmailAddress, feature: String): Future[ElectronicMail] = {
    emailTriggers.get(feature).map {
      case (htmlTpl, textTpl) =>
        val emailToSend = EmailToSend(
          title = "kifi — Boom! You're on the wait list",
          fromName = Some(Right("Kifi")),
          from = SystemEmailAddress.NOTIFICATIONS,
          to = Right(email),
          subject = "You're on the wait list",
          category = NotificationCategory.User.WAITLIST,
          htmlTemplate = htmlTpl(),
          textTemplate = Some(textTpl()),
          campaign = Some(s"${feature}_waitlist"))
        emailTemplateSender.send(emailToSend)
    }.getOrElse(Future.failed(new IllegalArgumentException(s"unrecognized feature $feature")))
  }
}

