package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.eliza.commanders.NotificationJsonMaker
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.model.NormalizedURI
import com.keepit.notify.info._
import com.keepit.notify.model.event.LegacyNotification
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.store.ElizaS3ExternalIdImageStore
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }

class ElizaNotificationInfo @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    elizaS3ExternalIdImageStore: ElizaS3ExternalIdImageStore,
    notificationJsonMaker: NotificationJsonMaker,
    implicit val executionContext: ExecutionContext) {

  def toRawNotification(item: NotificationItem): (JsValue, Option[Id[NormalizedURI]]) = {
    item.event match {
      case legacy: LegacyNotification => (legacy.json, legacy.uriId)
      case _ => throw new IllegalArgumentException(s"Asked to make a raw notification out of $item, an incompatible item")
    }
  }

  def mkJson(notif: Notification, items: Set[NotificationItem], notifInfo: NotificationInfo): Future[JsValue] = {
    val maxByTime = items.maxBy(_.event.time)
    val maxTime = maxByTime.event.time
    notifInfo match {
      case info: StandardNotificationInfo =>
        Future.successful(Json.obj(
          "id" -> maxByTime.externalId,
          "time" -> maxTime,
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
        notificationJsonMaker.makeOne((json, notif.unread, uriId), true).map(_.obj)
    }
  }

  def resolveImage(image: NotificationImage): String = image match {
    case UserImage(user) => elizaS3ExternalIdImageStore.avatarUrlByUser(user)
    case PublicImage(url) => url
  }

}
