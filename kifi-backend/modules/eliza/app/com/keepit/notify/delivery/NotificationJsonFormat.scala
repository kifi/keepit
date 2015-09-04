package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.eliza.commanders.{ NotificationCommander, NotificationJsonMaker }
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model._
import com.keepit.model.NormalizedURI
import com.keepit.notify.info._
import com.keepit.notify.model.{ EmailRecipient, UserRecipient, Recipient }
import com.keepit.notify.model.event.LegacyNotification
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.RoverUriSummary
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import com.keepit.store.ElizaS3ExternalIdImageStore
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

class NotificationJsonFormat @Inject() (
    db: Database,
    shoeboxServiceClient: ShoeboxServiceClient,
    elizaS3ExternalIdImageStore: ElizaS3ExternalIdImageStore,
    notificationJsonMaker: NotificationJsonMaker,
    roverServiceClient: RoverServiceClient,
    notificationCommander: NotificationCommander,
    implicit val executionContext: ExecutionContext) {

  def toRawNotification(item: NotificationItem): (JsValue, Option[Id[NormalizedURI]]) = {
    item.event match {
      case legacy: LegacyNotification => (legacy.json, legacy.uriId)
      case _ => throw new IllegalArgumentException(s"Asked to make a raw notification out of $item, an incompatible item")
    }
  }

  def resolveImage(image: NotificationImage): String = image match {
    case UserImage(user) => elizaS3ExternalIdImageStore.avatarUrlByUser(user)
    case PublicImage(url) => url
  }

  def basicJson(notifWithInfo: NotificationWithInfo): Future[JsValue] = notifWithInfo match {
    case NotificationWithInfo(notif, items, info) =>
      val relevantItem = notifWithInfo.relevantItem
      notifWithInfo.info match {
        case info: StandardNotificationInfo =>
          Future.successful(Json.obj(
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
          ))
        case info: LegacyNotificationInfo =>
          val (json, uriId) = toRawNotification(items.head)
          notificationJsonMaker.makeOne((json, notif.unread, uriId), includeUriSummary = true).map(_.obj)
      }
  }

  def getUriSummary(notifId: Id[Notification]): Future[Option[RoverUriSummary]] = {
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

  def getParticipants(notif: Notification): Future[Set[BasicUserLikeEntity]] = {
    val participants = notificationCommander.getParticipants(notif)
    val userIds = participants.collect {
      case UserRecipient(userId, _) => userId
    }

    val userParticipantsF = shoeboxServiceClient.getBasicUsers(userIds.toSeq)
      .map(_.values.map(u => BasicUserLikeEntity(u)))

    val otherParticipants = participants.collect {
      case EmailRecipient(address) =>
        val participant = NonUserEmailParticipant(address)
        BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(participant))
    }

    for {
      userParticipants <- userParticipantsF
    } yield {
      userParticipants.toSet | otherParticipants
    }
  }

  def extendedJson(notifWithInfo: NotificationWithInfo, uriSummary: Boolean = false): Future[JsValue] = {
    notifWithInfo match {
      case NotificationWithInfo(notif, items, info) =>
        val notifId = notif.id.get
        val uriSummaryF = getUriSummary(notifId)
        val participantsF = getParticipants(notif)
        val jsonF = for {
          uriSummary <- uriSummaryF
        } yield {

        }
        null
    }
  }

}
