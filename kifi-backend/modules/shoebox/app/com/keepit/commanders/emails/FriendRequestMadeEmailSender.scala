package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.{ EmailTips, EmailToSend }
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ NotificationCategory, PasswordResetRepo, User }

import scala.concurrent.Future

class FriendRequestMadeEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    passwordResetRepo: PasswordResetRepo,
    basicUserRepo: BasicUserRepo,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(toUserId: Id[User], friendUserId: Id[User], category: NotificationCategory): Future[ElectronicMail] = {
    val friendUser = db.readOnlyReplica { implicit session => basicUserRepo.load(friendUserId) }

    val (subject, campaign) = category match {
      case NotificationCategory.User.CONNECTION_MADE =>
        val subject = s"You are now friends with ${friendUser.firstName} ${friendUser.lastName} on Kifi!"
        (subject, Some("connectionMade"))
      case NotificationCategory.User.FRIEND_ACCEPTED =>
        val subject = s"${friendUser.firstName} ${friendUser.lastName} accepted your Kifi friend request"
        (subject, Some("friendRequestAccepted"))
    }

    val emailToSend = EmailToSend(
      fromName = Some(s"${friendUser.firstName} ${friendUser.lastName} (via Kifi)"),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = subject,
      to = Left(toUserId),
      category = category,
      htmlTemplate = views.html.email.friendConnectionMadeBlack(toUserId, friendUserId, category),
      campaign = campaign,
      tips = Seq(EmailTips.FriendRecommendations)
    )
    emailTemplateSender.send(emailToSend)
  }
}
