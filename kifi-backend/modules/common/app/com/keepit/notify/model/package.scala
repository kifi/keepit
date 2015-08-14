package com.keepit.notify

import com.keepit.common.db.ExternalId
import com.keepit.notify.info.NotificationInfo
import play.api.libs.json.{ OFormat, Format }
import play.api.libs.functional.syntax._
import play.api.libs.json._

package object model {

  /**
   * Writing [[NotificationKind[_ <: NotificationEvent]] again and again is not fun.
   */
  type NKind = NotificationKind[_ <: NotificationEvent]

  /**
   * Represents the "id" of a set of events or items to something that doesn't know about a notification.
   *
   * This is computer in the companion object to Notificationitem
   */
  type NotificationId = ExternalId[Set[NotificationEvent]]

  object NotificationId {

    def apply(id: String): NotificationId = ExternalId(id)

    implicit val notificationIdFormat: Format[NotificationId] = Format(
      __.read[NotificationId],
      __.write[NotificationId]
    )

  }

  implicit def notificationIdMapFormat[A](implicit aFormat: Format[A]): Format[Map[NotificationId, A]] = Format(
    __.read[Map[String, A]].map(m => m.map { case (id, value) => (NotificationId(id), value) }),
    __.write[Map[String, A]].contramap(m => m.map { case (id, value) => (id.id, value) })
  )

}
