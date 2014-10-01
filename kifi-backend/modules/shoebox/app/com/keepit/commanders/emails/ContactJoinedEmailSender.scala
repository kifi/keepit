package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.fullName
import com.keepit.model.{ User, NotificationCategory }

import scala.concurrent.Future

class ContactJoinedEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(toUserId: Id[User], contactUserId: Id[User]): Future[ElectronicMail] =
    sendToUser(toUserId, contactUserId)

  def sendToUser(toUserId: Id[User], contactUserId: Id[User]): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      fromName = Some(Left(contactUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${fullName(contactUserId)} joined Kifi. Want to connect?",
      to = Left(toUserId),
      category = NotificationCategory.User.EMAIL_CONFIRMATION,
      htmlTemplate = views.html.email.black.emailConfirmation(userFirstName, verifyUrl),
      textTemplate = Some(views.html.email.black.emailConfirmationText(userFirstName, verifyUrl)),
      campaign = Some("contactJoined")
    )
    emailTemplateSender.send(emailToSend)
  }
}
