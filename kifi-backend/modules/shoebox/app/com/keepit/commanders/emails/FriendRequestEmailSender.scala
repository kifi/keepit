package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.social.BasicUserRepo
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ NotificationCategory, User, PasswordResetRepo }

import scala.concurrent.Future

class FriendRequestEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    passwordResetRepo: PasswordResetRepo,
    basicUserRepo: BasicUserRepo,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(toUserId: Id[User], fromUserId: Id[User]): Future[ElectronicMail] = {
    val requestingUser = db.readOnlyReplica { implicit session => basicUserRepo.load(fromUserId) }
    val emailToSend = EmailToSend(
      fromName = Some(Left(fromUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${requestingUser.firstName} ${requestingUser.lastName} sent you a friend request.",
      to = Left(toUserId),
      category = NotificationCategory.User.FRIEND_REQUEST,
      htmlTemplate = views.html.email.black.friendRequest(toUserId, fromUserId),
      textTemplate = Some(views.html.email.black.friendRequestText(toUserId, fromUserId)),
      campaign = Some("friendRequest")
    )
    emailTemplateSender.send(emailToSend)
  }
}
