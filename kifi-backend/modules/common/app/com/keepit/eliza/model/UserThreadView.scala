package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.NotificationCategory.NonUser
import com.keepit.model.{ NormalizedURI, User }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ JsResult, JsString, Json, JsError, JsSuccess, JsValue, Format }

@json case class MessageView(from: MessageSenderView, messageText: String, createdAt: DateTime)

@json case class UserThreadView(
  pageTitle: Option[String],
  uriId: Option[Id[NormalizedURI]],
  lastSeen: Option[DateTime],
  lastActive: Option[DateTime],
  messages: Seq[MessageView])

object MessageSenderView {
  val USER = "u"
  val NON_USER = "n"
  val SYSTEM = "s"

  private val jsKindField = "k"
  private val jsDataField = "d"

  implicit val format: Format[MessageSenderView] = new Format[MessageSenderView] {

    def reads(json: JsValue): JsResult[MessageSenderView] = {
      val kind: String = (json \ jsKindField).as[String]
      kind match {
        case USER =>
          val userId = (json \ jsDataField).as[Id[User]]
          JsSuccess(MessageSenderUserView(userId))
        case NON_USER =>
          val identifier = (json \ jsDataField).as[String]
          JsSuccess(MessageSenderNonUserView(identifier))
        case SYSTEM => JsSuccess(MessageSenderSystemView())
        case _ => JsError(kind)
      }
    }

    def writes(obj: MessageSenderView): JsValue = obj match {
      case MessageSenderUserView(userId) => Json.obj(jsKindField -> USER, jsDataField -> Json.toJson(userId))
      case MessageSenderNonUserView(identifier) => Json.obj(jsKindField -> USER, jsDataField -> JsString(identifier))
      case MessageSenderSystemView() => Json.obj(jsKindField -> SYSTEM)
    }

  }
}

sealed trait MessageSenderView {
  def kind: String
}

case class MessageSenderUserView(id: Id[User]) extends MessageSenderView {
  val kind = MessageSenderView.USER
}

case class MessageSenderNonUserView(identifier: String) extends MessageSenderView {
  val kind = MessageSenderView.NON_USER
}

case class MessageSenderSystemView() extends MessageSenderView {
  val kind = MessageSenderView.SYSTEM
}
