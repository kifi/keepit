package com.keepit.realtime

import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.serializer.CommentWithBasicUserSerializer._
import com.keepit.common.social.CommentWithBasicUserRepo
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
import com.keepit.common.logging._
import com.keepit.common.net.URINormalizer
import com.keepit.common.db.{State, Id}
import com.keepit.common.controller.FortyTwoServices


case class CommentDetails(
  id: String,
  author: BasicUser,
  recipient: BasicUser,
  url: String,
  page: String,
  title: String,
  text: String,
  createdAt: DateTime,
  newCount: Int,
  totalCount: Int,
  subsumes: Option[String]
)

case class MessageDetails(
  id: String,
  authors: Seq[BasicUser],
  recipient: BasicUser,
  url: Option[String],
  page: Option[String],
  title: Option[String],
  text: String,
  createdAt: DateTime,
  hasParent: Boolean,
  newCount: Int,
  totalCount: Int,
  subsumes: Option[String]
)

case class SendableNotification(
  id: ExternalId[UserNotification],
  time: DateTime,
  category: UserNotificationCategory,
  details: UserNotificationDetails,
  state: State[UserNotification]
)

object SendableNotification {
  def fromUserNotification(notify: UserNotification) = {
    SendableNotification(id = notify.externalId, time = notify.updatedAt, category = notify.category, details = notify.details, state = notify.state)
  }
}

class NotificationBroadcaster @Inject() (userChannel: UserChannel) extends Logging {
  import com.keepit.serializer.SendableNotificationSerializer
  def push(notify: UserNotification) {
    val sendable = SendableNotification.fromUserNotification(notify)
    log.info("User notification serialized: " + sendable)
    userChannel.push(notify.userId, Json.arr("notification", SendableNotificationSerializer.sendableNotificationSerializer.writes(sendable)))
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
  CommentWithBasicUserRepo: CommentWithBasicUserRepo,
  basicUserRepo: BasicUserRepo,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  userNotifyRepo: UserNotificationRepo,
  notificationBroadcast: NotificationBroadcaster,
  implicit val fortyTwoServices: FortyTwoServices) extends Logging {

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
          commentId = comment.id,
          subsumedId = None
        ))

        notificationBroadcast.push(userNotification)
        notifyCommentByEmail(user, commentDetail)

        userNotifyRepo.save(userNotification.withState(UserNotificationStates.DELIVERED))
      }
    }
  }

  def message(message: Comment) = {
    // For now, we will email instantly & push streaming notifications.
    // Soon, we will email only when the user did not get the notification.
    db.readWrite { implicit s =>

            // I would love to avoid this, but simply passing the last message id from the thread (before this comment)
      // means users can't post two messages in a row. We actually have to get the thread, and check (backwards)
      // for the first post that isn't by the current author.
      val conversationId = message.parent.getOrElse(message.id.get)
      val thread = (message.parent.map(commentRepo.get).getOrElse(message) +: commentRepo.getChildren(conversationId)).reverse

      val messageDetails = createMessageDetails(message, thread)

      log.info(thread.mkString("\n"))

      userNotifyRepo.getWithCommentId(message.userId, conversationId)

      messageDetails.map { case (userId, messageDetail) =>
        val lastNotifiedMessage = thread.find(c => c.id != message.id && c.userId != userId)

        val lastNotice = (lastNotifiedMessage match {
          case Some(lastMsg) =>
            userNotifyRepo.getWithCommentId(userId, lastMsg.id.get) map { oldNotice =>
              userNotifyRepo.save(oldNotice.withState(UserNotificationStates.SUBSUMED)).id
            }
          case None => None
        }).flatten

        val user = userRepo.get(messageDetail.recipient.externalId)
        val userNotification = userNotifyRepo.save(UserNotification(
          userId = user.id.get,
          category = UserNotificationCategories.MESSAGE,
          details = UserNotificationDetails(Json.toJson(messageDetail)),
          commentId = message.id,
          subsumedId = lastNotice
        ))

        notificationBroadcast.push(userNotification)
        notifyMessageByEmail(user, messageDetail)

        userNotifyRepo.save(userNotification.withState(UserNotificationStates.DELIVERED))
      }
    }
  }

  private def notifyCommentByEmail(recipient: User, details: CommentDetails)(implicit session: RSession) = {
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
  private def notifyMessageByEmail(recipient: User, details: MessageDetails)(implicit session: RSession) = {
    val author = userRepo.get(details.authors.head.externalId)
    val addrs = emailAddressRepo.getByUser(recipient.id.get)
    for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
      postOffice.sendMail(ElectronicMail(
          senderUserId = author.id,
          from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
          to = addr,
          subject = "%s %s sent you a message using KiFi".format(author.firstName, author.lastName),
          htmlBody = replaceLookHereLinks(
              views.html.email.newMessage(author, recipient, details.url.getOrElse(""), details.title.getOrElse("No title"), details.text, details.hasParent).body
          ),
          category = PostOffice.Categories.COMMENT))
    }
  }

  private def createCommentDetails(comment: Comment)(implicit session: RWSession): Set[CommentDetails] = {
    implicit val bus = BasicUserSerializer.basicUserSerializer

    val author = userRepo.get(comment.userId)
    val uri = normalizedURIRepo.get(comment.uriId)
    val follows = followRepo.getByUri(uri.id.get)
    for (userId <- follows.map(_.userId).toSet - comment.userId) yield {
      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
          initatorUserId = Option(comment.userId),
          recipientUserId = Some(userId),
          uriId = Some(comment.uriId),
          urlId = comment.urlId,
          deepLocator = DeepLocator.ofComment(comment)))
      new CommentDetails(
        comment.externalId.id,
        basicUserRepo.load(comment.userId),
        basicUserRepo.load(userId),
        deepLink.url,
        URINormalizer.normalize(uri.url),
        comment.pageTitle,
        comment.text,
        comment.createdAt,
        0,0, None
      )
    }
  }

  private def createMessageDetails(message: Comment, thread: Seq[Comment])(implicit session: RWSession): Set[(Id[User], MessageDetails)] = {
    implicit val bus = BasicUserSerializer.basicUserSerializer

    val author = userRepo.get(message.userId)
    val uri = normalizedURIRepo.get(message.uriId)
    val participants = commentRepo.getParticipantsUserIds(message.id.get)
    val parent = message.parent.map(commentRepo.get).getOrElse(message)

    for (userId <- participants - author.id.get) yield {

      val recentAuthors = thread.filter(c => c.userId != userId).map(_.userId).take(5)
      val authors = recentAuthors.map(basicUserRepo.load)

      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
          initatorUserId = Option(message.userId),
          recipientUserId = Some(userId),
          uriId = Some(message.uriId),
          urlId = message.urlId,
          deepLocator = DeepLocator.ofMessageThread(parent)))
      (userId -> new MessageDetails(
        message.externalId.id,
        authors,
        basicUserRepo.load(userId),
        Some(deepLink.url),
        Some(URINormalizer.normalize(uri.url)),
        Some(message.pageTitle),
        message.text,
        message.createdAt,
        message.parent.isDefined,
        0,0, None
      ))
    }
  }


  private val lookHereLinkRe = """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)""".r
  //e.g. [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7-video-container)
  private def replaceLookHereLinks(text: String): String =
    lookHereLinkRe.replaceAllIn(
        text, m => "[" + m.group(1).replaceAll("""\\(.)""", "$1") + "]")

}
