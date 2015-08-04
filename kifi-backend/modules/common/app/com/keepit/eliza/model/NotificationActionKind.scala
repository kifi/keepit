package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._

trait NotificationActionKind[O] {

  val name: String

  protected def getData(fromUser: Id[User], toUser: Id[User], time: DateTime, obj: O): Option[JsObject]

  def buildFrom(fromUser: Id[User], toUser: Id[User], time: DateTime, obj: O): NotificationAction = {
    new NotificationAction(this, fromUser, toUser, time, getData(fromUser, toUser, time, obj))
  }

  NotificationActionKind.addKind(this)

}

object NotificationActionKind {

  private var kinds: Map[String, NotificationActionKind[_]] = Map()

  private def addKind(kind: NotificationActionKind[_]) = {
    kinds = kinds + (kind.name -> kind)
  }

  def getByName(name: String): Option[NotificationActionKind[_]] = kinds.get(name)

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

trait EmptyNotificationActionKind extends NotificationActionKind[Unit] {

  override def getData(fromUser: Id[User], toUser: Id[User], time: DateTime, obj: Unit): Option[JsObject] = None

  def buildFrom(fromUser: Id[User], time: DateTime, toUser: Id[User]) = {
    buildFrom(fromUser, toUser, time, ())
  }

}

object ConnectionRequest extends EmptyNotificationActionKind {

  override val name: String = "connection_request"

}
