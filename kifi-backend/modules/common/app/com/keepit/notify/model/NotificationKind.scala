package com.keepit.notify.model

import play.api.libs.json._

import scala.annotation.implicitNotFound

@implicitNotFound("No kind object found for action ${N}")
trait NotificationKind[N] {

  val name: String

  implicit val format: Format[N]

  implicit val selfCompanion: NotificationKind[ N] = this

  def shouldGroupWith(newAction: N, existingActions: Set[N]): Boolean

  NotificationKind.addKind(this)

}

object NotificationKind {

  private var kinds: Map[String, NotificationKind[_]] = Map()

  private def addKind(kind: NotificationKind[_]) = {
    kinds = kinds + (kind.name -> kind)
  }

  def getByName(name: String): Option[NotificationKind[_]] = kinds.get(name)

  implicit val format = new Format[NotificationKind[_]] {

    override def reads(json: JsValue): JsResult[NotificationKind[_]] = {
      val kindName = json.as[String]
      getByName(kindName).fold[JsResult[NotificationKind[_]]](JsError(s"Notification action kind $kindName does not exist")) { kind =>
        JsSuccess(kind)
      }
    }

    override def writes(o: NotificationKind[_]): JsValue = Json.toJson(o.name)

  }

}
