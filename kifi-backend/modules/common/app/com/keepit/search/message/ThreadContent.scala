package com.keepit.search.index.message

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.social.{ BasicUserLikeEntity }
import com.keepit.model.User

import play.api.libs.json._
import play.api.libs.functional.syntax._

import org.joda.time.DateTime

sealed trait ThreadContentUpdateMode
case object DIFF extends ThreadContentUpdateMode //not supported yet
case object FULL extends ThreadContentUpdateMode

object ThreadContentUpdateMode {
  implicit val format = new Format[ThreadContentUpdateMode] {

    def reads(json: JsValue): JsResult[ThreadContentUpdateMode] = json match {
      case JsString("FULL") => JsSuccess(FULL)
      case JsString("DIFF") => JsSuccess(DIFF)
      case _ => JsError()
    }

    def writes(obj: ThreadContentUpdateMode) = obj match {
      case FULL => JsString("FULL")
      case DIFF => JsString("DIFF")
      case _ => JsNull
    }

  }
}

case class ThreadContent(
  mode: ThreadContentUpdateMode,
  id: Id[ThreadContent],
  seq: SequenceNumber[ThreadContent],
  participants: Seq[BasicUserLikeEntity],
  updatedAt: DateTime,
  url: String,
  threadExternalId: String,
  pageTitleOpt: Option[String],
  digest: String,
  content: Seq[String],
  participantIds: Seq[Id[User]])

object ThreadContent {

  implicit val userIdFormat = Id.format[User]

  implicit def format = (
    (__ \ 'mode).format[ThreadContentUpdateMode] and
    (__ \ 'id).format(Id.format[ThreadContent]) and
    (__ \ 'seq).format(SequenceNumber.format[ThreadContent]) and
    (__ \ 'participants).format[Seq[BasicUserLikeEntity]] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'url).format[String] and
    (__ \ 'threadExternalId).format[String] and
    (__ \ 'pageTitleOpt).formatNullable[String] and
    (__ \ 'digest).format[String] and
    (__ \ 'content).format[Seq[String]] and
    (__ \ 'participantIds).format[Seq[Id[User]]]
  )(ThreadContent.apply, unlift(ThreadContent.unapply))
}
