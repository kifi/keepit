package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress, EmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.NotificationCategory

import scala.concurrent.Future

class FeatureWaitlistEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  val emailTriggers = Map(
    "mobile_app" -> views.html.email.mobileWaitlistBlack
  )

  def sendToUser(email: EmailAddress, feature: String): Future[ElectronicMail] = {
    emailTriggers.get(feature).map { template =>
      val emailToSend = EmailToSend(
        title = "kifi — Boom! You're on the wait list",
        fromName = Some("Kifi"),
        from = SystemEmailAddress.NOTIFICATIONS,
        to = Right(email),
        subject = "You're on the wait list",
        category = NotificationCategory.User.WAITLIST,
        htmlTemplate = template(),
        campaign = Some(s"${feature}_waitlist")
      )
      emailTemplateSender.send(emailToSend)
    }.getOrElse(Future.failed(new IllegalArgumentException(s"unrecognized feature $feature")))
  }
}

