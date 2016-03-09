package com.keepit.notify.delivery

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.common.time._
import com.keepit.eliza.commanders.NotificationJsonMaker
import com.keepit.eliza.model._
import com.keepit.model.{ Keep, NormalizedURI, NotificationCategory }
import com.keepit.notify.info._
import com.keepit.notify.model.event.{ LibraryNewKeep, LegacyNotification }
import play.api.libs.json.{ JsValue, Json }

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class NotificationJsonFormat @Inject() (
    notificationJsonMaker: NotificationJsonMaker,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val s3ImageConfig: S3ImageConfig,
    implicit val executionContext: ExecutionContext) {

  private def toRawNotification(item: NotificationItem): (JsValue, Option[Id[NormalizedURI]]) = {
    item.event match {
      case legacy: LegacyNotification => (legacy.json, legacy.uriId)
      case _ => throw new IllegalArgumentException(s"Asked to make a raw notification out of $item, an incompatible item")
    }
  }

  private def resolveImage(image: NotificationImage): String = image match {
    case UserImage(user) => user.picturePath.getUrl
    case OrganizationImage(org) => org.avatarPath.getUrl
    case PublicImage(url) => url
  }

  def getNotificationThreadStr(notification: Notification, item: NotificationItem): String = {
    item.event match {
      case newKeep: LibraryNewKeep => Keep.publicId(newKeep.keepId).id
      case _ => notification.externalId.id
    }
  }

  def basicJson(notifWithInfo: NotificationWithInfo): Future[NotificationWithJson] = notifWithInfo match {
    case NotificationWithInfo(notif, items, info) =>
      val relevantItem = notifWithInfo.relevantItem
      notifWithInfo.info match {
        case info: StandardNotificationInfo =>
          val thread = getNotificationThreadStr(notif, relevantItem)
          Future.successful(NotificationWithJson(notif, items, Json.obj(
            "id" -> relevantItem.externalId,
            "time" -> relevantItem.eventTime,
            "thread" -> thread,
            "unread" -> Json.toJson(notif.unread),
            "category" -> Json.toJson(
              NotificationCategory.User.kifiMessageFormattingCategory.getOrElse(info.category, "global")
            ),
            "fullCategory" -> info.category.category,
            "title" -> info.title,
            "bodyHtml" -> info.body,
            "linkText" -> info.linkText,
            "url" -> info.url,
            "isSticky" -> false,
            "image" -> resolveImage(info.image),
            "locator" -> info.locator,
            "extra" -> info.extraJson
          )))
        case info: LegacyNotificationInfo =>
          val (json, uriId) = toRawNotification(items.head)
          notificationJsonMaker.makeOne((json, notif.unread, uriId), includeUriSummary = true).map(_.obj).map { json =>
            NotificationWithJson(notif, items, json ++ Json.obj(
              "id" -> items.head.externalId,
              "thread" -> notif.externalId
            ))
          }
      }
  }

  private val idealImageSize = ImageSize(65, 95) // todo figure out where these somewhat magic image size numbers are needed

  def extendedJson(notifWithInfo: NotificationWithInfo, uriSummary: Boolean = false): Future[NotificationWithJson] = {
    notifWithInfo match {
      case NotificationWithInfo(notif, items, info) =>
        val notifId = notif.id.get
        val basicFormatF = basicJson(notifWithInfo)
        for {
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

          val json = basicFormat.json ++ unreadJson
          NotificationWithJson(notif, items, json)
        }
    }
  }

}
