package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.{ EmailTips, EmailToSend }
import com.keepit.common.mail.template.helpers.{ fullName, firstName }
import com.keepit.model.{ NotificationCategory, User }
import com.keepit.social.SocialNetworkType
import com.keepit.social.SocialNetworks.{ FACEBOOK, LINKEDIN }

class FriendConnectionMadeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(toUserId: Id[User], friendUserId: Id[User], category: NotificationCategory, networkTypeOpt: Option[SocialNetworkType] = None) =
    sendToUser(toUserId, friendUserId, category, networkTypeOpt)

  def sendToUser(toUserId: Id[User], friendUserId: Id[User], category: NotificationCategory, networkTypeOpt: Option[SocialNetworkType] = None) = {
    // sanity-check to ensure we don't print other network types to users
    val networkNameOpt = networkTypeOpt collect { case FACEBOOK | LINKEDIN => networkTypeOpt.get.displayName }

    val (subject, campaign) = category match {
      case NotificationCategory.User.FRIEND_ACCEPTED =>
        val subject = s"${fullName(friendUserId)} accepted your Kifi friend request"
        (subject, Some("friendRequestAccepted"))
      case NotificationCategory.User.SOCIAL_FRIEND_JOINED if networkNameOpt.isDefined =>
        val subject = s"Your ${networkNameOpt.get} friend ${firstName(friendUserId)} just joined Kifi"
        (subject, Some("socialFriendJoined"))
      case _ =>
        val subject = s"You are now friends with ${fullName(friendUserId)} on Kifi!"
        (subject, Some("connectionMade"))
    }

    val emailToSend = EmailToSend(
      fromName = Some(Left(friendUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = subject,
      to = Left(toUserId),
      category = category,
      htmlTemplate = views.html.email.black.friendConnectionMade(toUserId, friendUserId, category, networkNameOpt),
      textTemplate = Some(views.html.email.black.friendConnectionMadeText(toUserId, friendUserId, category, networkNameOpt)),
      campaign = campaign,
      tips = Seq(EmailTips.FriendRecommendations)
    )
    emailTemplateSender.send(emailToSend)
  }
}
