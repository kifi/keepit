package com.keepit.realtime

import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.serializer.CommentWithSocialUserSerializer._
import com.keepit.common.social.CommentWithSocialUserRepo
import com.keepit.common.db.slick.DBSession._
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserRepo
import com.keepit.serializer.BasicUserSerializer
import com.keepit.model.UserNotificationDetails
import org.joda.time.DateTime
import com.keepit.model.UserNotificationDetails
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.ExternalId


case class CommentDetails(author: BasicUser, recipient: BasicUser, url: String, title: String, text: String, createdAt: DateTime)
case class MessageDetails(author: BasicUser, recipient: BasicUser, url: Option[String], title: Option[String], text: String, createdAt: DateTime, isParent: Boolean)

case class SendableNotification(
  id: ExternalId[UserNotification],
  createdAt: DateTime,
  updatedAt: DateTime,
  category: UserNotificationCategory,
  details: UserNotificationDetails
)

object SendableNotification {
  def fromUserNotification(notify: UserNotification) = {
    SendableNotification(id = notify.externalId, createdAt = notify.createdAt, updatedAt = notify.updatedAt, category = notify.category, details = notify.details)
  }
}

class NotificationBroadcaster @Inject() (userNotification: UserNotificationStreamManager) {
  import com.keepit.serializer.SendableNotificationSerializer
  def push(notify: UserNotification) = {
    val sendable = SendableNotification.fromUserNotification(notify)
    userNotification.push(notify.userId, "notification", SendableNotificationSerializer.sendableNotificationSerializer.writes(sendable))
  }
}

@Singleton
class UserNotifier @Inject() (
  db: Database,
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  followRepo: FollowRepo,
  emailAddressRepo: EmailAddressRepo,
  deepLinkRepo: DeepLinkRepo,
  postOffice: PostOffice,
  commentWithSocialUserRepo: CommentWithSocialUserRepo,
  basicUserRepo: BasicUserRepo,
  commentRepo: CommentRepo,
  userNotifyRepo: UserNotificationRepo,
  userNotifyStream: UserNotificationStreamManager,
  notificationBroadcast: NotificationBroadcaster) {


  implicit val basicUserFormat = BasicUserSerializer.basicUserSerializer
  implicit val commentDetailsFormat = Json.format[CommentDetails]
  implicit val messageDetailsFormat = Json.format[MessageDetails]

  def comment(comment: Comment): Unit = {
    // For now, we will email instantly & push streaming notifications.
    // Soon, we will email only when the user did not get the notification.
    db.readWrite { implicit s =>
      val commentDetails = createCommentDetails(comment)
      commentDetails.map { commentDetail =>
        val user = userRepo.get(commentDetail.recipient.externalId)
        val userNotification = userNotifyRepo.save(UserNotification(
          userId = user.id.get,
          category = UserNotificationCategories.COMMENT,
          details = UserNotificationDetails(Json.toJson(commentDetail)),
          commentId = comment.id
        ))

        notifyCommentByEmail(user, commentDetail)
        notificationBroadcast.push(userNotification)
      }
    }
  }

  def message(message: Comment /* Can't wait for this to change! */) = {
    // For now, we will email instantly & push streaming notifications.
    // Soon, we will email only when the user did not get the notification.
    db.readWrite { implicit s =>
      val messageDetails = createMessageDetails(message)
      messageDetails.map { messageDetail =>
        val user = userRepo.get(messageDetail.recipient.externalId)
        val userNotification = userNotifyRepo.save(UserNotification(
          userId = user.id.get,
          category = UserNotificationCategories.MESSAGE,
          details = UserNotificationDetails(Json.toJson(messageDetail)),
          commentId = message.id
        ))

        notifyMessageByEmail(user, messageDetail)
        notificationBroadcast.push(userNotification)
      }
    }
  }

  def notifyCommentByEmail(recipient: User, details: CommentDetails)(implicit session: RSession) = {
    val author = userRepo.get(details.author.externalId)
    val addrs = emailAddressRepo.getByUser(recipient.id.get)
    for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
      postOffice.sendMail(ElectronicMail(
          senderUserId = author.id,
          from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
          to = addr,
          subject = "%s %s commented on a page you are following".format(author.firstName, author.lastName),
          htmlBody = replaceLookHereLinks(views.html.email.newComment(author, recipient, details.url, details.title, details.text).body),
          category = PostOffice.Categories.COMMENT))
    }
  }
  def notifyMessageByEmail(recipient: User, details: MessageDetails)(implicit session: RSession) = {
    val author = userRepo.get(details.author.externalId)
    val addrs = emailAddressRepo.getByUser(recipient.id.get)
    for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
      postOffice.sendMail(ElectronicMail(
          senderUserId = author.id,
          from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
          to = addr,
          subject = "%s %s sent you a message using KiFi".format(author.firstName, author.lastName),
          htmlBody = replaceLookHereLinks(views.html.email.newMessage(author, recipient, details.url.getOrElse(""), details.title.getOrElse("No title"), details.text, details.isParent).body),
          category = PostOffice.Categories.COMMENT))
    }
  }

  def createCommentDetails(comment: Comment)(implicit session: RWSession): Set[CommentDetails] = {
    implicit val bus = BasicUserSerializer.basicUserSerializer

    val author = userRepo.get(comment.userId)
    val uri = normalizedURIRepo.get(comment.uriId)
    val follows = followRepo.getByUri(uri.id.get)
    for (userId <- follows.map(_.userId).toSet - comment.userId + comment.userId) yield {
      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
          initatorUserId = Option(comment.userId),
          recipientUserId = Some(userId),
          uriId = Some(comment.uriId),
          urlId = comment.urlId,
          deepLocator = DeepLocator.ofComment(comment)))
      new CommentDetails(basicUserRepo.load(comment.userId), basicUserRepo.load(userId), deepLink.url, comment.pageTitle, comment.text, comment.createdAt)
    }
  }

  def createMessageDetails(message: Comment)(implicit session: RWSession): Set[MessageDetails] = {
    implicit val bus = BasicUserSerializer.basicUserSerializer

    val author = userRepo.get(message.userId)
    val uri = normalizedURIRepo.get(message.uriId)
    val participants = commentRepo.getParticipantsUserIds(message.id.get)
    for (userId <- participants - author.id.get + author.id.get) yield {
      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
          initatorUserId = Option(message.userId),
          recipientUserId = Some(userId),
          uriId = Some(message.uriId),
          urlId = message.urlId,
          deepLocator = DeepLocator.ofMessageThread(message)))
      new MessageDetails(basicUserRepo.load(message.userId), basicUserRepo.load(userId), Some(deepLink.url), Some(message.pageTitle), message.text, message.createdAt, message.parent.isDefined)
    }
  }



  //e.g. [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7-video-container)
  def replaceLookHereLinks(text: String): String =
    """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)""".r.replaceAllIn(
        text, m => "[" + m.group(1).replaceAll("""\\(.)""", "$1") + "]")

}