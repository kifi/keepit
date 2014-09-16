package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.fullName
import com.keepit.common.social.BasicUserRepo
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ User, NotificationCategory }

import scala.concurrent.Future

class ContactJoinedEmailSender @Inject() (
    basicUserRepo: BasicUserRepo,
    emailTemplateSender: EmailTemplateSender,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(toUserId: Id[User], otherUserId: Id[User]): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      fromName = Some(Left(otherUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${fullName(otherUserId)} joined Kifi. Want to connect?",
      to = Left(toUserId),
      category = NotificationCategory.User.CONTACT_JOINED,
      htmlTemplate = views.html.email.black.contactJoined(toUserId, otherUserId),
      textTemplate = Some(views.html.email.black.contactJoinedText(toUserId, otherUserId)),
      campaign = Some("contactJoined")
    )
    emailTemplateSender.send(emailToSend)
  }
}
