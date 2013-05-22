package com.keepit.realtime

import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.serializer.CommentWithBasicUserSerializer._
import com.keepit.common.social.CommentWithBasicUserRepo
import com.keepit.common.db.slick.DBSession._
import play.api.libs.json._
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.UserNotificationDetails
import org.joda.time.DateTime
import com.keepit.model.UserNotificationDetails
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.ExternalId
import com.keepit.common.logging._
import com.keepit.common.net.URINormalizer
import com.keepit.common.db.{ State, Id }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.ThreadInfoRepo
import com.keepit.serializer.ThreadInfoSerializer._
import com.keepit.common.healthcheck._
import com.keepit.common.akka._
import com.keepit.common.time._

case class CommentDetails(
  id: String, // ExternalId[Comment]
  author: BasicUser,
  recipient: BasicUser,
  locator: String, // DeepLink.deepLocator
  url: String, // DeepLink.url (containing /r/)
  page: String, // NormalizedURI.url
  title: String, // Comment.pageTitle
  text: String, // Comment.text
  createdAt: DateTime,
  newCount: Int,
  totalCount: Int,
  subsumes: Option[String] // Option[ExternalId[UserNotification]]
  )

case class MessageDetails(
  id: String, // ExternalId[Comment] of the message
  hasParent: Boolean,
  authors: Seq[BasicUser],
  recipient: BasicUser,
  locator: String, // DeepLink.deepLocator
  url: String, // DeepLink.url (containing /r/)
  page: String, // NormalizedURI.url
  title: String, // Comment.pageTitle
  text: String, // Comment.text
  createdAt: DateTime,
  newCount: Int,
  totalCount: Int,
  subsumes: Option[String] // Option[ExternalId[UserNotification]]
  )

case class SendableNotification(
  id: ExternalId[UserNotification],
  time: DateTime,
  category: UserNotificationCategory,
  details: UserNotificationDetails,
  state: State[UserNotification])

object SendableNotification {
  def fromUserNotification(notify: UserNotification) = {
    SendableNotification(id = notify.externalId, time = notify.createdAt, category = notify.category,
      details = notify.details, state = notify.state)
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
  postOffice: LocalPostOffice,
  basicUserRepo: BasicUserRepo,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  commentFormatter: CommentFormatter,
  userNotifyRepo: UserNotificationRepo,
  notificationBroadcast: NotificationBroadcaster,
  commentWithBasicUserRepo: CommentWithBasicUserRepo,
  normalUriRepo: NormalizedURIRepo,
  uriChannel: UriChannel,
  userChannel: UserChannel,
  threadInfoRepo: ThreadInfoRepo,
  implicit val fortyTwoServices: FortyTwoServices) extends Logging {

  implicit val commentDetailsFormat = Json.format[CommentDetails]
  implicit val messageDetailsFormat = Json.format[MessageDetails]

  def comment(comment: Comment) {
    db.readWrite { implicit s =>
      val normalizedUri = normalUriRepo.get(comment.uriId).url
      uriChannel.push(normalizedUri, Json.arr("comment", normalizedUri, commentWithBasicUserRepo.load(comment)))

      val commentDetails = createCommentDetails(comment)
      commentDetails.map { commentDetail =>
        val user = userRepo.get(commentDetail.recipient.externalId)
        val userNotification = userNotifyRepo.save(UserNotification(
          userId = user.id.get,
          category = UserNotificationCategories.COMMENT,
          details = UserNotificationDetails(Json.toJson(commentDetail)),
          commentId = comment.id,
          subsumedId = None))
        notificationBroadcast.push(userNotification)
        //notifyCommentByEmail(user, commentDetail)

        //userNotifyRepo.save(userNotification.withState(UserNotificationStates.DELIVERED))
      }
    }
  }

  def message(message: Comment) {
    db.readWrite { implicit s =>
      val normUri = normalUriRepo.get(message.uriId)
      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      val threadInfo = threadInfoRepo.load(parent, Some(message.userId))
      val messageJson = Json.arr("message", normUri.url, threadInfo, commentWithBasicUserRepo.load(message))
      userChannel.push(message.userId, messageJson)

      val thread = if (message eq parent) Seq(message) else (parent +: commentRepo.getChildren(parent.id.get)).reverse

      createMessageUserNotifications(message, thread) map {
        case (user, messageDetails, userNotification) =>

          if (userChannel.isConnected(userNotification.userId)) {
            log.info(s"Sending notification because ${userNotification.userId} is connected.")

            userChannel.push(userNotification.userId, messageJson)
            notificationBroadcast.push(userNotification)
          } else {
            log.info(s"Sending email because ${userNotification.userId} is not connected.")
            //notifyMessageByEmail(user, messageDetails)
          }

        //userNotifyRepo.save(userNotification.withState(UserNotificationStates.DELIVERED))
      }
    }
  }

  def recreateMessageDetails(safeMode: Boolean)(implicit session: RWSession) = {
    userNotifyRepo.allActive(UserNotificationCategories.MESSAGE) map { notice =>
      try {
        val recipient = userRepo.get(notice.userId)
        val message = commentRepo.get(notice.commentId.get)

        val deepLinkUrl = (notice.details.payload \ "url").as[String]
        val deepLinkLocator = (notice.details.payload \ "locator").asOpt[String].getOrElse {
          // We haven't always had this field. When it's missing, we need to find it.
          val token = deepLinkUrl.split("/").last
          deepLinkRepo.getByToken(DeepLinkToken(token)).map(_.deepLocator.value).get
        }
        val page = (notice.details.payload \ "page").as[String]

        val lastNoticeExtId = notice.subsumedId.map(userNotifyRepo.get(_).externalId)

        val parent = message.parent.map(commentRepo.get).getOrElse(message)
        val thread = if (message eq parent) Seq(message) else (parent +: commentRepo.getChildren(parent.id.get)).reverse

        val messageDetail = createMessageDetail(message, notice.userId, DeepLocator(deepLinkLocator), deepLinkUrl, page, thread, lastNoticeExtId)
        val json = Json.toJson(messageDetail)
        if (json != notice.details.payload) {
          log.info(s"Updating ${notice.id} (message, safeMode: $safeMode).\n-Old details-\n${notice.details.payload}\n-New details-\n$json")
          if (!safeMode) userNotifyRepo.save(notice.copy(details = UserNotificationDetails(json)))
        }
      } catch {
        case ex: Throwable =>
          log.warn(s"[recreateDetail] Error: Could not recreate message notice ${notice.id}", ex)
      }

    }
  }

  def recreateCommentDetails(safeMode: Boolean)(implicit session: RWSession) = {
    userNotifyRepo.allActive(UserNotificationCategories.COMMENT) map { notice =>
      try {
        val comment = commentRepo.get(notice.commentId.get)
        val author = userRepo.get(comment.userId)
        val uri = normalizedURIRepo.get(comment.uriId)
        val userId = notice.userId
        val recipient = userRepo.get(userId)
        val deepLinkUrl = (notice.details.payload \ "url").as[String]
        val deepLinkLocator = (notice.details.payload \ "locator").asOpt[String].getOrElse {
          // We haven't always had this field. When it's missing, we need to find it.
          val token = deepLinkUrl.split("/").last
          deepLinkRepo.getByToken(DeepLinkToken(token)).map(_.deepLocator.value).get
        }
        val commentDetail = new CommentDetails(
          comment.externalId.id,
          basicUserRepo.load(comment.userId),
          basicUserRepo.load(userId),
          deepLinkLocator,
          deepLinkUrl,
          URINormalizer.normalize(uri.url),
          comment.pageTitle,
          comment.text,
          comment.createdAt,
          0, 0, None)
        val json = Json.toJson(commentDetail)
        if (json != notice.details.payload) {
          log.info(s"[recreateDetail] Updating ${notice.id} (comment, safeMode: $safeMode).\n-Old details-\n${notice.details.payload}\n-New details-\n$json")
          if (!safeMode) userNotifyRepo.save(notice.copy(details = UserNotificationDetails(json)))
        }
      } catch {
        case ex: Throwable =>
          log.warn(s"[recreateDetail] Error: Could not recreate comment notice ${notice.id}", ex)
      }

    }
  }

  def recreateAllActiveDetails(safeMode: Boolean)(implicit session: RWSession) = {
    recreateMessageDetails(safeMode)
    recreateCommentDetails(safeMode)
  }

  private def createCommentDetails(comment: Comment)(implicit session: RWSession): Set[CommentDetails] = {
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
        deepLocator = DeepLocator.ofCommentList))
      new CommentDetails(
        comment.externalId.id,
        basicUserRepo.load(comment.userId),
        basicUserRepo.load(userId),
        deepLink.deepLocator.value,
        deepLink.url,
        URINormalizer.normalize(uri.url),
        comment.pageTitle,
        comment.text,
        comment.createdAt,
        0, 0, None)
    }
  }

  private def createMessageUserNotifications(message: Comment, thread: Seq[Comment])(implicit session: RWSession): Set[(User, MessageDetails, UserNotification)] = {
    val author = userRepo.get(message.userId)
    val uri = normalizedURIRepo.get(message.uriId)
    val participants = commentRepo.getParticipantsUserIds(message.id.get)
    val parent = message.parent.map(commentRepo.get).getOrElse(message)

    val generatedSet = for (userId <- participants - author.id.get) yield {

      val lastNotifiedMessage = thread.find(c => c.id != message.id && c.userId != userId)
      val lastNotice = lastNotifiedMessage flatMap { lastMsg =>
        userNotifyRepo.getWithCommentId(userId, lastMsg.id.get) map { oldNotice =>
          userNotifyRepo.save(oldNotice.withState(UserNotificationStates.SUBSUMED))
        }
      }

      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
        initatorUserId = Option(message.userId),
        recipientUserId = Some(userId),
        uriId = Some(message.uriId),
        urlId = message.urlId,
        deepLocator = DeepLocator.ofMessageThread(parent)))

      val messageDetail = createMessageDetail(message, userId, deepLink.deepLocator, deepLink.url, uri.url, thread, lastNotice.map(_.externalId))

      val user = userRepo.get(userId)
      (recipient, messageDetail, userNotifyRepo.save(UserNotification(
        userId = userId,
        category = UserNotificationCategories.MESSAGE,
        details = UserNotificationDetails(Json.toJson(messageDetail)),
        commentId = message.id,
        subsumedId = lastNotice.map(_.id.get))))
    }
    generatedSet
  }

  private def createMessageDetail(
    message: Comment, userId: Id[User],
    deepLocator: DeepLocator,
    deepLinkUrl: String,
    page: String,
    thread: Seq[Comment],
    lastNoticeExtId: Option[ExternalId[UserNotification]])(implicit session: RWSession): MessageDetails = {
    val recentAuthors = thread.filter(c => c.userId != userId).map(_.userId).distinct.take(5)
    val authors = recentAuthors.map(basicUserRepo.load)

    new MessageDetails(
      message.externalId.id,
      message.parent.isDefined,
      authors,
      basicUserRepo.load(userId),
      deepLocator.value,
      deepLinkUrl,
      URINormalizer.normalize(page),
      message.pageTitle,
      message.text,
      message.createdAt,
      1,
      thread.size,
      lastNoticeExtId.map(_.id))
  }

}
