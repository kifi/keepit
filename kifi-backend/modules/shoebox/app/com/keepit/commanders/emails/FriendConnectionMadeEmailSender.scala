package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.{ fullName, firstName, profileUrl }
import com.keepit.model.{ NotificationCategory, User }
import com.keepit.social.SocialNetworkType
import com.keepit.social.SocialNetworks.{ FACEBOOK, LINKEDIN }

sealed case class ConnectionMadeEmailValues(line1: Option[String], line2: String)

class FriendConnectionMadeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(toUserId: Id[User], friendUserId: Id[User], category: NotificationCategory, networkTypeOpt: Option[SocialNetworkType] = None) =
    sendToUser(toUserId, friendUserId, category, networkTypeOpt)

  def sendToUser(toUserId: Id[User], friendUserId: Id[User], category: NotificationCategory, networkTypeOpt: Option[SocialNetworkType] = None) = {
    def friendSourceName = networkTypeOpt collect {
      case FACEBOOK => FACEBOOK.displayName + " friend"
      case LINKEDIN => LINKEDIN.displayName + " connection"
    } getOrElse "friend"

    val (emailPlainText, emailHtmlText, subject, campaign) = category match {
      case NotificationCategory.User.FRIEND_ACCEPTED =>
        val emailHtmlText = ConnectionMadeEmailValues(
          line1 = None,
          line2 = s"""<a href="${profileUrl(friendUserId, "friendRequestAccepted")}">${fullName(friendUserId)}</a> accepted your Kifi friend request""")
        val emailPlainText = ConnectionMadeEmailValues(
          line1 = None,
          line2 = s"""${fullName(friendUserId)} accepted your Kifi friend request""")
        val subject = s"${fullName(friendUserId)} accepted your Kifi friend request"
        (emailPlainText, emailHtmlText, subject, Some("friendRequestAccepted"))
      case NotificationCategory.User.SOCIAL_FRIEND_JOINED if networkTypeOpt.isDefined =>
        val emailHtmlText = ConnectionMadeEmailValues(
          line1 = Some(s"""Your $friendSourceName, <a href="${profileUrl(friendUserId, "friendRequestAccepted")}">${fullName(friendUserId)}</a>, joined Kifi"""),
          line2 = s"""You and <a href="${profileUrl(friendUserId, "friendRequestAccepted")}">${fullName(friendUserId)}</a> are now connected on Kifi""")
        val emailPlainText = ConnectionMadeEmailValues(
          line1 = Some(s"""Your $friendSourceName, ${fullName(friendUserId)}, joined Kifi"""),
          line2 = s"""You and ${fullName(friendUserId)} are now connected on Kifi""")
        val subject = s"Your $friendSourceName ${firstName(friendUserId)} just joined Kifi"
        (emailPlainText, emailHtmlText, subject, Some("socialFriendJoined"))
      case NotificationCategory.User.CONNECTION_MADE =>
        val emailHtmlText = ConnectionMadeEmailValues(
          line1 = Some("You have a new connection on Kifi"),
          line2 = s"""Your $friendSourceName, <a href="${profileUrl(friendUserId, "friendRequestAccepted")}">${fullName(friendUserId)}</a>, is now connected to you on Kifi""")
        val emailPlainText = ConnectionMadeEmailValues(
          line1 = Some("You have a new connection on Kifi"),
          line2 = s"""Your $friendSourceName, ${fullName(friendUserId)}, is now connected to you on Kifi""")
        val subject = s"You are now friends with ${fullName(friendUserId)} on Kifi!"
        (emailPlainText, emailHtmlText, subject, Some("connectionMade"))
    }

    val emailToSend = EmailToSend(
      fromName = Some(Left(friendUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = subject,
      to = Left(toUserId),
      category = category,
      htmlTemplate = views.html.email.black.friendConnectionMade(toUserId, friendUserId, emailHtmlText),
      textTemplate = Some(views.html.email.black.friendConnectionMadeText(toUserId, friendUserId, emailPlainText)),
      campaign = campaign,
      tips = Seq()
    )
    emailTemplateSender.send(emailToSend)
  }
}
