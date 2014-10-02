package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.fullName
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ UserEmailAddress, User, NotificationCategory }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class EmailConfirmationSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier,
    fortytwoConfig: FortyTwoConfig) extends Logging {

  def apply(emailAddr: UserEmailAddress): Future[ElectronicMail] =
    sendToUser(emailAddr)

  def sendToUser(emailAddr: UserEmailAddress): Future[ElectronicMail] = {

    val siteUrl = fortytwoConfig.applicationBaseUrl
    val verifyUrl = s"$siteUrl${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
    val toUserId = emailAddr.userId

    val emailToSend = EmailToSend(
      fromName = None,
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = "Kifi.com | Please confirm your email address",
      to = Right(emailAddr.address),
      category = NotificationCategory.User.EMAIL_CONFIRMATION,
      htmlTemplate = views.html.email.verifyEmail(toUserId, verifyUrl)
    )
    emailTemplateSender.send(emailToSend)
  }
}
