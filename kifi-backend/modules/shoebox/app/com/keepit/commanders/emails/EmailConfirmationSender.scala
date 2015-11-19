package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ EmailAddress, ElectronicMail, SystemEmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ Organization, EmailVerificationCode, User, NotificationCategory, UserEmailAddress }

import scala.concurrent.Future

class EmailConfirmationSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier,
    fortytwoConfig: FortyTwoConfig) extends Logging {

  def apply(emailAddr: UserEmailAddress, domainOwnerIds: Set[Id[Organization]]): Future[ElectronicMail] =
    sendToUser(emailAddr.userId, emailAddr.address, emailAddr.verificationCode.get, domainOwnerIds)

  def sendToUser(toUserId: Id[User], address: EmailAddress, verificationCode: EmailVerificationCode, domainOwnerIds: Set[Id[Organization]]): Future[ElectronicMail] = {

    val siteUrl = fortytwoConfig.applicationBaseUrl
    val verifyUrl = s"$siteUrl${EmailVerificationCode.verifyPath(verificationCode)}"

    val toAddr = { // used to test a fake kyfy.com email domain without having access to it, and not spamming @kifi.com
      if (address.address.endsWith("@kyfy.com")) EmailAddress("kyfy.com$".r.replaceFirstIn(address.address, "kifi.com"))
      else address
    }

    val emailToSend = EmailToSend(
      fromName = None,
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = "Kifi.com | Please confirm your email address",
      to = Right(toAddr),
      category = NotificationCategory.User.EMAIL_CONFIRMATION,
      htmlTemplate = views.html.email.verifyEmail(toUserId, verifyUrl, domainOwnerIds),
      textTemplate = Some(views.html.email.verifyEmailText(toUserId, verifyUrl, domainOwnerIds))
    )
    emailTemplateSender.send(emailToSend)
  }
}
