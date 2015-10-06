package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, ElectronicMail, SystemEmailAddress }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.controllers.core.routes
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ PasswordResetRepo, NotificationCategory, User }

import scala.concurrent.{ ExecutionContext, Future }

class ResetPasswordEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    passwordResetRepo: PasswordResetRepo,
    config: FortyTwoConfig,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(userId: Id[User], resetEmailAddress: EmailAddress): Future[ElectronicMail] = {
    val reset = db.readWrite { implicit rw =>
      passwordResetRepo.createNewResetToken(userId, resetEmailAddress)
    }

    val resetUrl = config.applicationBaseUrl + routes.AuthController.setPasswordPage(reset.token)
    val emailToSend = EmailToSend(
      fromName = Some(Right("Kifi Support")),
      from = SystemEmailAddress.SUPPORT,
      subject = "Kifi.com | Password reset requested",
      to = Right(resetEmailAddress),
      category = NotificationCategory.User.RESET_PASSWORD,
      htmlTemplate = views.html.email.black.resetPassword(userId, resetUrl),
      textTemplate = Some(views.html.email.black.resetPassword(userId, resetUrl)),
      campaign = Some("passwordReset")
    )
    emailTemplateSender.send(emailToSend)
  }
}
