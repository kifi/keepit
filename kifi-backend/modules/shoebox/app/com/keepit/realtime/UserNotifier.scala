package com.keepit.realtime


import scala.collection.JavaConverters._
import scala.concurrent.Lock

import java.util.concurrent.ConcurrentHashMap

import org.joda.time.DateTime

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ State, Id }
import com.keepit.common.logging._
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.social.{ThreadInfo, CommentWithBasicUser}

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.normalizer.NormalizationService

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

case class GlobalNotificationDetails(
  createdAt: DateTime,
  title: String,
  bodyHtml: String,
  linkText: String,
  url: Option[String],
  image: String,
  isSticky: Boolean,
  markReadOnAction: Boolean
)
object GlobalNotificationDetails {
  implicit val globalDetailsFormat = Json.format[GlobalNotificationDetails]
}

case class SendableNotification(
  id: ExternalId[UserNotification],
  time: DateTime,
  category: UserNotificationCategory,
  details: UserNotificationDetails,
  state: State[UserNotification])

object SendableNotification {
  implicit val format = (
    (__ \ 'id).format(ExternalId.format[UserNotification]) and
    (__ \ 'time).format(DateTimeJsonFormat) and
    (__ \ 'category).format[String].inmap(UserNotificationCategory.apply, unlift(UserNotificationCategory.unapply)) and
    (__ \ 'details).format[JsValue].inmap(UserNotificationDetails.apply, unlift(UserNotificationDetails.unapply)) and
    (__ \ 'state).format(State.format[UserNotification])
  )(SendableNotification.apply, unlift(SendableNotification.unapply))

  def fromUserNotification(notify: UserNotification) = {
    SendableNotification(id = notify.externalId, time = notify.createdAt, category = notify.category,
      details = notify.details, state = notify.state)
  }
}

class NotificationBroadcaster @Inject() (
    urbanAirship: UrbanAirship,
    userNotificationRepo: UserNotificationRepo,
    messageRepo: CommentRepo,
    db: Database
  ) extends Logging {
  def push(notify: UserNotification) {
    lazy val unvisitedCount = db.readOnly { implicit s => userNotificationRepo.getUnvisitedCount(notify.userId) }
    for (pushNotification <- PushNotification.fromUserNotification(notify, unvisitedCount)) {
      log.info("Push notification: " + pushNotification)
      urbanAirship.notifyUser(notify.userId, pushNotification)
    }
    val sendable = SendableNotification.fromUserNotification(notify)
    log.info("User notification serialized: " + sendable)
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
  threadInfoRepo: ThreadInfoRepo,
  normalizationService: NormalizationService,
  clock: Clock,
  implicit val fortyTwoServices: FortyTwoServices) extends Logging {

  private val threadLocks = new ConcurrentHashMap[Id[Comment], Lock]().asScala
  private def withThreadLock[A](comment: Comment)(block: => A): A = {
    val lock = threadLocks.getOrElseUpdate(comment.parent getOrElse comment.id.get, new Lock)
    try {
      lock.acquire()
      block
    } finally {
      lock.release()
    }
  }

  implicit val commentDetailsFormat = Json.format[CommentDetails]
  implicit val messageDetailsFormat = Json.format[MessageDetails]

  def globalNotification(global: GlobalNotification) = {
    db.readWrite { implicit session =>
      val users = global.sendToSpecificUsers.getOrElse {
        userRepo.allExcluding(UserStates.BLOCKED, UserStates.PENDING).map(_.id.get)
      }
      val globalDetails = GlobalNotificationDetails(
        createdAt = clock.now,
        title = global.title,
        bodyHtml = global.bodyHtml,
        linkText = global.linkText,
        url = global.url,
        image = global.image,
        isSticky = global.isSticky,
        markReadOnAction = global.markReadOnAction)

      val globalDetailsJson = UserNotificationDetails(Json.toJson(globalDetails))

      users.map { userId =>
        val userNotification = userNotifyRepo.save(UserNotification(
          userId = userId,
          category = UserNotificationCategories.GLOBAL,
          details = globalDetailsJson,
          commentId = None,
          subsumedId = None,
          state = UserNotificationStates.DELIVERED))
        notificationBroadcast.push(userNotification)
      }
    }
  }

  def comment(comment: Comment): Unit = {
    db.readWrite { implicit s =>
      val normalizedUri = normalUriRepo.get(comment.uriId).url

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
      }
    }
  }

  def message(message: Comment): Unit = withThreadLock(message) {
    val SPECIAL_MESSAGE = "Hi, Kifi messages are down due to a system upgrade so your message was not sent. The upgrade should be finished this afternoon (PST). Check http://kifiupdates.tumblr.com/ for updates. Sorry for the inconvenience, and thanks for helping us build Kifi!"

    //val (thread, participants) = 
    db.readOnly { implicit s =>
      val normUri = normalUriRepo.get(message.uriId)
      val parent = message
      // val threadInfo = threadInfoRepo.load(parent, Some(message.userId))
      val bu = basicUserRepo.load(message.userId)
      val threadInfo = ThreadInfo(
        externalId = message.externalId,
        recipients = Seq(),
        digest = message.text,
        lastAuthor = bu.externalId,
        messageCount = 1,
        messageTimes = Map((Seq(message)).map {c => c.externalId -> c.createdAt}: _*),
        createdAt = message.createdAt,
        lastCommentedAt = message.createdAt
      )


      // val cwbu = commentWithBasicUserRepo.load(message)

      val cwbu = CommentWithBasicUser(
        id=message.externalId,
        createdAt=message.createdAt,
        text=SPECIAL_MESSAGE,
        user=bu.copy(firstName="Kifi", lastName=""),
        permissions= CommentPermissions.MESSAGE,
        recipients= Seq()
      )
      
      // val cwbu2 = cwbu.copy(text=SPECIAL_MESSAGE)
      
      val messageJson = Json.arr("message", normUri.url, threadInfo, cwbu)
      // val participants = commentRepo.getParticipantsUserIds(message.id.get)
      // participants.map { p =>
      //   userChannel.pushAndFanout(p, messageJson)
      // }

      //val thread = if (message eq parent) Seq(message) else (parent +: commentRepo.getChildren(parent.id.get)).reverse

      //(thread, participants)
    }

    // db.readWrite { implicit s =>
    //   createMessageUserNotifications(message, thread, participants) map {
    //     case (messageDetails, userNotification) =>
    //       log.info(s"Sending notification to ${userNotification.userId}: $messageDetails")
    //       notificationBroadcast.push(userNotification)
    //   }
    // }
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
          normalizationService.normalize(uri.url),
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
    val uri = normalizedURIRepo.get(comment.uriId)
    val follows = followRepo.getByUri(uri.id.get)
    for (userId <- follows.map(_.userId).toSet - comment.userId) yield {
      val deepLink = deepLinkRepo.save(DeepLink(
        initiatorUserId = Option(comment.userId),
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
        normalizationService.normalize(uri.url),
        comment.pageTitle,
        comment.text,
        comment.createdAt,
        0, 0, None)
    }
  }

  private def createMessageUserNotifications(message: Comment, thread: Seq[Comment], participants: Set[Id[User]])(implicit session: RWSession): Set[(MessageDetails, UserNotification)] = {
    val author = userRepo.get(message.userId)
    val uri = normalizedURIRepo.get(message.uriId)
    val parent = message.parent.map(commentRepo.get).getOrElse(message)

    val generatedSet = (for (userId <- participants - author.id.get) yield {
      val lastMessage +: olderMessages = thread.filter(_.userId != userId)
      val subsumed = olderMessages flatMap { m =>
        userNotifyRepo.getWithCommentId(userId, m.id.get)
      } map { oldNotice =>
        userNotifyRepo.save(oldNotice.withState(UserNotificationStates.SUBSUMED))
      }
      if (lastMessage.id.get == message.id.get) {
        val lastNotice = subsumed.headOption
        val recipient = userRepo.get(userId)
        val deepLink = deepLinkRepo.save(DeepLink(
          initiatorUserId = Option(message.userId),
          recipientUserId = Some(userId),
          uriId = Some(message.uriId),
          urlId = message.urlId,
          deepLocator = DeepLocator.ofMessageThread(parent)))

        val messageDetail = createMessageDetail(message, userId, deepLink.deepLocator, deepLink.url, uri.url, thread, lastNotice.map(_.externalId))
        Some((messageDetail, userNotifyRepo.save(UserNotification(
          userId = userId,
          category = UserNotificationCategories.MESSAGE,
          details = UserNotificationDetails(Json.toJson(messageDetail)),
          commentId = message.id,
          subsumedId = lastNotice.map(_.id.get)))))
      } else {
        // This message is not the last one in the thread so it should already be subsumed
        // Therefore, we don't need to generate a notification
        None
      }
    }).flatten
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
      normalizationService.normalize(page),
      message.pageTitle,
      message.text,
      message.createdAt,
      1,
      thread.size,
      lastNoticeExtId.map(_.id))
  }

}
