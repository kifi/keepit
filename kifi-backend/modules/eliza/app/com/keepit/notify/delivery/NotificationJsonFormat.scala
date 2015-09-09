package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.common.JsObjectExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.{ S3ImageConfig, ImageSize }
import com.keepit.eliza.commanders.{ MessageWithBasicUser, NotificationCommander, NotificationJsonMaker }
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model._
import com.keepit.model.{ NotificationCategory, NormalizedURI }
import com.keepit.notify.info._
import com.keepit.notify.model.{ EmailRecipient, UserRecipient, Recipient }
import com.keepit.notify.model.event.{ NewMessage, LegacyNotification }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.RoverUriSummary
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import com.keepit.store.ElizaS3ExternalIdImageStore
import play.api.libs.json.{ JsObject, Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

class NotificationJsonFormat @Inject() (
    db: Database,
    shoeboxServiceClient: ShoeboxServiceClient,
    elizaS3ExternalIdImageStore: ElizaS3ExternalIdImageStore,
    notificationJsonMaker: NotificationJsonMaker,
    messageThreadRepo: MessageThreadRepo,
    messageRepo: MessageRepo,
    userThreadRepo: UserThreadRepo,
    roverServiceClient: RoverServiceClient,
    notificationCommander: NotificationCommander,
    implicit val s3ImageConfig: S3ImageConfig,
    implicit val executionContext: ExecutionContext) {

  private def toRawNotification(item: NotificationItem): (JsValue, Option[Id[NormalizedURI]]) = {
    item.event match {
      case legacy: LegacyNotification => (legacy.json, legacy.uriId)
      case _ => throw new IllegalArgumentException(s"Asked to make a raw notification out of $item, an incompatible item")
    }
  }

  private def resolveImage(image: NotificationImage): String = image match {
    case UserImage(user) => elizaS3ExternalIdImageStore.avatarUrlByUser(user)
    case PublicImage(url) => url
  }

  def threadInfo(notifWithInfo: NotificationWithInfo): Future[JsObject] = {
    notifWithInfo match {
      case NotificationWithInfo(notif, items, MessageNotificationInfo(messages)) =>
        val mostRecent = messages.maxBy(_.time)
        messageInfo(notifWithInfo, mostRecent)
    }
  }

  def messageInfo(notifWithInfo: NotificationWithInfo, messageNotif: NewMessage): Future[JsObject] = {
    notifWithInfo match {
      case NotificationWithInfo(notif, items, MessageNotificationInfo(messages)) =>
        val (message, messageThread, threadActivity, numMessages, numUnread) = db.readOnlyReplica { implicit session =>
          val messageThreadId = Id[MessageThread](messageNotif.messageThreadId)
          val (numMessages, numUnread) = messageRepo.getMessageCounts(messageThreadId, notif.lastChecked)
          (messageRepo.get(Id[Message](messageNotif.messageId)),
            messageThreadRepo.get(messageThreadId),
            userThreadRepo.getThreadActivity(messageThreadId).toList, numMessages, numUnread)
        }

        val authorActivities = threadActivity.filter(_.lastActive.isDefined)
        val originalAuthor = authorActivities.filter(_.started).zipWithIndex.head._2
        val unseenAuthors = notif.lastChecked.fold(authorActivities.length) { checked =>
          authorActivities.toList.count(_.lastActive.get.isAfter(checked))
        }

        for {
          (sender, participants) <- getParticipants(notif)
        } yield {
          Json.obj(
            "id" -> items.maxBy(_.eventTime).externalId,
            "time" -> messageNotif.time,
            "thread" -> notif.externalId,
            "text" -> message.messageText,
            "url" -> messageThread.nUrl,
            "title" -> messageThread.pageTitle,
            "author" -> sender,
            "locator" -> ("/messages/" + messageThread.externalId.id),
            "unread" -> notif.unread,
            "category" -> NotificationCategory.User.MESSAGE.category,
            "firstAuthor" -> originalAuthor,
            "authors" -> authorActivities.length,
            "messages" -> numMessages,
            "unreadAuthors" -> unseenAuthors,
            "unreadMessages" -> numUnread,
            "muted" -> notif.disabled
          )
        }
    }
  }

  def basicJson(notifWithInfo: NotificationWithInfo): Future[NotificationWithJson] = notifWithInfo match {
    case NotificationWithInfo(notif, items, info) =>
      val relevantItem = notifWithInfo.relevantItem
      notifWithInfo.info match {
        case info: StandardNotificationInfo =>
          Future.successful(NotificationWithJson(notif, items, Json.obj(
            "id" -> relevantItem.externalId,
            "time" -> relevantItem.eventTime,
            "thread" -> notif.externalId,
            "unread" -> Json.toJson(notif.unread),
            "category" -> "triggered",
            "fullCategory" -> "replace me", // todo replace
            "title" -> info.title,
            "bodyHtml" -> info.body,
            "linkText" -> info.linkText,
            "url" -> info.url,
            "isSticky" -> false,
            "image" -> resolveImage(info.image),
            "extra" -> info.extraJson
          )))
        case info: LegacyNotificationInfo =>
          val (json, uriId) = toRawNotification(items.head)
          notificationJsonMaker.makeOne((json, notif.unread, uriId), includeUriSummary = true).map(_.obj).map { json =>
            NotificationWithJson(notif, items, json)
          }
        case info: MessageNotificationInfo =>
          threadInfo(notifWithInfo).map { json =>
            NotificationWithJson(notif, items, json)
          }
      }
  }

  private def getUriSummary(notifId: Id[Notification]): Future[Option[RoverUriSummary]] = {
    val uriOpt = notificationCommander.getURI(notifId)
    uriOpt.map { uri =>
      roverServiceClient.getUriSummaryByUris(Set(uri)).map { uriMap =>
        uriMap.toSeq.head match {
          case (_, summary) => summary
        }
      }
    }.fold(Future.successful[Option[RoverUriSummary]](None)) { uriSummary =>
      uriSummary.map { summary => Option(summary) }.recover {
        case NonFatal(e) => None
      }
    }
  }

  private def getParticipants(notif: Notification): Future[(BasicUserLikeEntity, Set[BasicUserLikeEntity])] = {
    val participants = notificationCommander.getParticipants(notif)
    val userIds = participants.collect {
      case UserRecipient(userId, _) => userId
    }

    val userParticipantsF = shoeboxServiceClient.getBasicUsers(userIds.toSeq)
      .map(_.mapValues(u => BasicUserLikeEntity(u)))

    val otherParticipants = participants.collect {
      case EmailRecipient(address) =>
        val participant = NonUserEmailParticipant(address)
        BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(participant))
    }

    for {
      userParticipants <- userParticipantsF
    } yield {
      val participantsSet = userParticipants.values.toSet | otherParticipants
      val recipientBasic = notif.recipient match {
        case UserRecipient(userId, _) => userParticipants(userId)
        case EmailRecipient(address) =>
          val participant = NonUserEmailParticipant(address)
          BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(participant))
      }
      participantsSet.partition(_ == recipientBasic) match {
        case (author, rest) =>
          (author.head, rest | author)
      }
    }
  }

  private val idealImageSize = ImageSize(65, 95) // todo figure out where these somewhat magic image size numbers are needed

  def extendedJson(notifWithInfo: NotificationWithInfo, uriSummary: Boolean = false): Future[NotificationWithJson] = {
    notifWithInfo match {
      case NotificationWithInfo(notif, items, info) =>
        val notifId = notif.id.get
        val uriSummaryF = getUriSummary(notifId)
        val participantsF = getParticipants(notif)
        val basicFormatF = basicJson(notifWithInfo)
        for {
          uriSummary <- uriSummaryF
          (author, participants) <- participantsF
          basicFormat <- basicFormatF
        } yield {
          val unreadJson =
            if (notif.unread)
              Json.obj(
                "unread" -> true,
                "unreadMessages" -> math.max(1, notifWithInfo.unreadMessages.size),
                "unreadAuthors" -> math.max(1, notifWithInfo.unreadAuthors.size)
              )
            else
              Json.obj(
                "unread" -> false,
                "unreadMessages" -> 0,
                "unreadAuthors" -> 0
              )

          val participantsJson =
            if (participants.isEmpty)
              Json.obj()
            else
              Json.obj("participants" -> participants)

          val uriSummaryJson = uriSummary.fold(Json.obj()) { summary =>
            val image = summary.images.get(idealImageSize)
            Json.obj("uriSummary" -> Json.obj(
              "title" -> summary.article.title,
              "description" -> summary.article.description,
              "imageUrl" -> image.map(_.path.getUrl),
              "imageWidth" -> image.map(_.size.width),
              "imageHeight" -> image.map(_.size.height
              )))
          }

          val json = basicFormat.json ++ Json.obj("author" -> author) ++ unreadJson ++
            participantsJson ++ uriSummaryJson

          NotificationWithJson(notif, items, json)
        }
    }
  }

}
