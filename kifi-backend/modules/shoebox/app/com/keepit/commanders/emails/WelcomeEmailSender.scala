package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ NotificationCategory, PasswordResetRepo }

import scala.concurrent.Future

class WelcomeEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    passwordResetRepo: PasswordResetRepo,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(userId: Id[User]): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      title = "Kifi — Welcome",
      fromName = Some("Kifi"),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = "Let's get started with Kifi",
      to = Left(userId),
      category = NotificationCategory.User.WELCOME,
      htmlTemplate = views.html.email.welcomeBlack(userId),
      campaign = Some("welcomeEmail")
    )
    emailTemplateSender.send(emailToSend)
  }
}
