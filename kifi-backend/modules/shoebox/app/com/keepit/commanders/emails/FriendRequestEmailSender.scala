package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.fullName
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.model.{ NotificationCategory, User }

import scala.concurrent.Future

class FriendRequestEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(toUserId: Id[User], fromUserId: Id[User]): Future[ElectronicMail] =
    sendToUser(toUserId, fromUserId)

  def sendToUser(toUserId: Id[User], fromUserId: Id[User]): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      fromName = Some(Left(fromUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${fullName(fromUserId)} wants to connect with you on Kifi",
      to = Left(toUserId),
      category = NotificationCategory.User.FRIEND_REQUEST,
      htmlTemplate = views.html.email.black.friendRequest(toUserId, fromUserId),
      textTemplate = Some(views.html.email.black.friendRequestText(toUserId, fromUserId)),
      campaign = Some("friendRequest")
    )
    emailTemplateSender.send(emailToSend)
  }
}
