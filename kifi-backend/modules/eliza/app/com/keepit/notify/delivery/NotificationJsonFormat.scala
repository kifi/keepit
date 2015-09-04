package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.eliza.commanders.NotificationJsonMaker
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model.{ NotificationWithInfo, Notification, NotificationItem }
import com.keepit.model.NormalizedURI
import com.keepit.notify.info._
import com.keepit.notify.model.event.LegacyNotification
import com.keepit.rover.RoverServiceClient
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.store.ElizaS3ExternalIdImageStore
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }

class NotificationJsonFormat @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    elizaS3ExternalIdImageStore: ElizaS3ExternalIdImageStore,
    notificationJsonMaker: NotificationJsonMaker,
    roverServiceClient: RoverServiceClient,
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
            "time" -> notif.lastEvent,
            "thread" -> notif.externalId,
            "unread" -> Json.toJson(notif.unread),
            "category" -> "triggered",
            "fullCategory" -> "replace me",
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

  def extendedJson(notif: Notification, items: Set[NotificationItem], notifInfo: NotificationInfo, uriSummary: Boolean = false): Future[JsValue] = {

  }

}
