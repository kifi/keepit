package com.keepit.commanders.emails

import com.keepit.common.logging.Logging
import com.google.inject.Inject
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress }
import com.keepit.common.mail.template.{ EmailTips, EmailToSend }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ NotificationCategory, PasswordResetRepo }

import scala.concurrent.Future

class FriendRequestAcceptedEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    passwordResetRepo: PasswordResetRepo,
    basicUserRepo: BasicUserRepo,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(toUserId: Id[User], friendUserId: Id[User]): Future[ElectronicMail] = {
    val friendUser = db.readOnlyReplica { implicit session => basicUserRepo.load(friendUserId) }
    val emailToSend = EmailToSend(
      fromName = Some(s"${friendUser.firstName} ${friendUser.lastName} (via Kifi)"),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${friendUser.firstName} ${friendUser.lastName} accepted your Kifi friend request",
      to = Left(toUserId),
      category = NotificationCategory.User.FRIEND_ACCEPTED,
      htmlTemplate = views.html.email.friendRequestAcceptedBlack(toUserId, friendUserId),
      campaign = Some("friendRequestAccepted"),
      tips = Seq(EmailTips.FriendRecommendations)
    )
    emailTemplateSender.send(emailToSend)
  }
}
