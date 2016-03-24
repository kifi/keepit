package com.keepit.notify.model

import com.keepit.common.reflection.CompanionTypeSystem
import com.keepit.notify.model.event._
import play.api.libs.json._

trait NotificationKind[N <: NotificationEvent] {

  val name: String

  implicit val format: Format[N]

  implicit val selfCompanion: NotificationKind[N] = this

  /**
   * Defines whether a new event of this kind should be grouped together with existing events in the same notification.
   *
   * @param newEvent The new events
   * @param existingEvents The existing events
   * @return True if the events should be grouped together, false otherwise
   */
  def shouldGroupWith(newEvent: N, existingEvents: Set[N]): Boolean
}

abstract class GroupingNotificationKind[N <: NotificationEvent, G](implicit val gid: GroupIdentifier[G]) extends NotificationKind[N] {

  def getIdentifier(that: N): G

}

/**
 * Defines a kind of notification that guarantees that it does not group events.
 */
trait NonGroupingNotificationKind[N <: NotificationEvent] extends NotificationKind[N] {

  final def shouldGroupWith(newEvent: N, existingEvents: Set[N]): Boolean = false

}

object NotificationKind {

  private val kinds: Set[NKind] = CompanionTypeSystem[NotificationEvent, NotificationKind[_ <: NotificationEvent]]("N")

  private val kindsByName: Map[String, NKind] = kinds.map(kind => kind.name -> kind).toMap

  def getByName(name: String): Option[NKind] = kindsByName.get(name)

  implicit val format = new Format[NKind] {

    override def reads(json: JsValue): JsResult[NKind] = {
      val kindName = json.as[String]
      getByName(kindName).fold[JsResult[NKind]](JsError(s"Notification action kind $kindName does not exist")) { kind =>
        JsSuccess(kind)
      }
    }

    override def writes(o: NKind): JsValue = Json.toJson(o.name)

  }

}
