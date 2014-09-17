package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.{ EmailTips, EmailToSend }
import com.keepit.common.mail.template.helpers.fullName
import com.keepit.common.mail.{ ElectronicMail, SystemEmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ NotificationCategory, PasswordResetRepo, User }

import scala.concurrent.Future

class FriendConnectionMadeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    passwordResetRepo: PasswordResetRepo,
    basicUserRepo: BasicUserRepo,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(toUserId: Id[User], friendUserId: Id[User], category: NotificationCategory): Future[ElectronicMail] = {
    val (subject, campaign) = category match {
      case NotificationCategory.User.CONNECTION_MADE =>
        val subject = s"You are now friends with ${fullName(friendUserId)} on Kifi!"
        (subject, Some("connectionMade"))
      case NotificationCategory.User.FRIEND_ACCEPTED =>
        val subject = s"${fullName(friendUserId)} accepted your Kifi friend request"
        (subject, Some("friendRequestAccepted"))
    }

    val emailToSend = EmailToSend(
      fromName = Some(Left(friendUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = subject,
      to = Left(toUserId),
      category = category,
      htmlTemplate = views.html.email.black.friendConnectionMade(toUserId, friendUserId, category),
      textTemplate = Some(views.html.email.black.friendConnectionMadeText(toUserId, friendUserId, category)),
      campaign = campaign,
      tips = Seq(EmailTips.FriendRecommendations)
    )
    emailTemplateSender.send(emailToSend)
  }
}
