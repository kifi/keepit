package com.keepit.notify.model

import play.api.libs.json._

import scala.annotation.implicitNotFound

@implicitNotFound("No kind object found for action ${N}")
trait NotificationKind[N] {

  val name: String

  implicit val format: Format[N]

  implicit val selfCompanion: NotificationKind[N] = this

  def shouldGroupWith(newAction: N, existingActions: Set[N]): Boolean

}

object NotificationKind {

  private val kinds: List[NotificationKind[_]] = List[NotificationKind[_]](
    NewFollower
  )

  private val kindsByName: Map[String, NotificationKind[_]] = kinds.map(kind => kind.name -> kind).toMap

  def getByName(name: String): Option[NotificationKind[_]] = kindsByName.get(name)

  implicit val format = new Format[NotificationKind[_]] {

    override def reads(json: JsValue): JsResult[NotificationKind[_]] = {
      val kindName = (json \ "kind").as[String]
      getByName(kindName).fold[JsResult[NotificationKind[_]]](JsError(s"Notification action kind $kindName does not exist")) { kind =>
        JsSuccess(kind)
      }
    }

    override def writes(o: NotificationKind[_]): JsValue = Json.toJson(o.name)

  }

}
