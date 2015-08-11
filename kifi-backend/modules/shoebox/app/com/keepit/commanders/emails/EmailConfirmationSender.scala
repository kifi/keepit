package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ EmailAddress, ElectronicMail, SystemEmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ User, NotificationCategory, UserEmailAddress }

import scala.concurrent.Future

class EmailConfirmationSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier,
    fortytwoConfig: FortyTwoConfig) extends Logging {

  def apply(emailAddr: UserEmailAddress): Future[ElectronicMail] =
    sendToUser(emailAddr.userId, emailAddr.address, emailAddr.verificationCode.get)

  def sendToUser(toUserId: Id[User], address: EmailAddress, verificationCode: String): Future[ElectronicMail] = {

    val siteUrl = fortytwoConfig.applicationBaseUrl
    val verifyUrl = s"$siteUrl${com.keepit.controllers.core.routes.AuthController.verifyEmail(verificationCode)}"

    val emailToSend = EmailToSend(
      fromName = None,
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = "Kifi.com | Please confirm your email address",
      to = Right(address),
      category = NotificationCategory.User.EMAIL_CONFIRMATION,
      htmlTemplate = views.html.email.verifyEmail(toUserId, verifyUrl),
      textTemplate = Some(views.html.email.verifyEmailText(toUserId, verifyUrl))
    )
    emailTemplateSender.send(emailToSend)
  }
}
