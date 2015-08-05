package com.keepit.eliza.model

import play.api.libs.json._

import scala.annotation.implicitNotFound

@implicitNotFound("No kind object found for action ${N}")
trait NotificationActionKind[ N] {

  val name: String

  implicit val format: Format[N]

  implicit val selfCompanion: NotificationActionKind[ N] = this

  def shouldGroupWith(newAction: N, existingActions: Set[N]): Boolean

  NotificationActionKind.addKind(this)

}

object NotificationActionKind {

  private var kinds: Map[String, NotificationActionKind[_]] = Map()

  private def addKind(kind: NotificationActionKind[_]) = {
    kinds = kinds + (kind.name -> kind)
  }

  def getByName(name: String): Option[NotificationActionKind[_]] = kinds.get(name)

  def kifiLink(path: String): String = s"https://kifi.com/$path"

  implicit val format = new Format[NotificationActionKind[_]] {

    override def reads(json: JsValue): JsResult[NotificationActionKind[_]] = {
      val kindName = json.as[String]
      getByName(kindName).fold[JsResult[NotificationActionKind[_]]](JsError(s"Notification action kind $kindName does not exist")) { kind =>
        JsSuccess(kind)
      }
    }

    override def writes(o: NotificationActionKind[_]): JsValue = Json.toJson(o.name)

  }

}
