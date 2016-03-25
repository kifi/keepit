package com.keepit.common.util

import com.keepit.common.db.Id
import com.keepit.common.reflection.Enumerator
import com.keepit.discussion.MessageSource
import com.keepit.model.User
import com.keepit.social.BasicNonUser
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class ActivityKind(val value: String)
object ActivityKind extends Enumerator[ActivityKind] {
  case object Initial extends ActivityKind("initial")
  case object Comment extends ActivityKind("comment")
  case object AddParticipants extends ActivityKind("add_participants")
  case object AddedLibrary extends ActivityKind("added_library")
  case object EditedTitle extends ActivityKind("edited_title")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get
  def contains(str: String) = all.exists(_.value == str)

  implicit val format: Format[ActivityKind] = Format(
    Reads { js => js.validate[String].map(ActivityKind.apply) },
    Writes { o => JsString(o.value) }
  )
}

sealed abstract class ActivitySource(val value: String)
object ActivitySource extends Enumerator[ActivitySource] {
  case object Slack extends ActivitySource("Slack")
  case object Twitter extends ActivitySource("Twitter")
  case object iOS extends ActivitySource("iOS")
  case object Android extends ActivitySource("Android")
  case object Chrome extends ActivitySource("Chrome") // refers to ext
  case object Firefox extends ActivitySource("Firefox")
  case object Safari extends ActivitySource("Safari")
  case object Email extends ActivitySource("Email")
  case object Site extends ActivitySource("Kifi.com")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get

  implicit val writes: Writes[ActivitySource] = Writes { o => JsString(o.value) }

  def fromMessageSource(msgSrc: Option[MessageSource]): Option[ActivitySource] = msgSrc.flatMap { src =>
    src match {
      case MessageSource.IPAD | MessageSource.IPHONE => Some(iOS)
      case MessageSource.CHROME | MessageSource.FIREFOX | MessageSource.SAFARI |
        MessageSource.ANDROID | MessageSource.EMAIL | MessageSource.SITE => Some(ActivitySource.apply(src.value))
      case _ => None
    }
  }
}

sealed abstract class ActivityEventData(val kind: ActivityKind)
object ActivityEventData {
  @json case class AddParticipants(addedBy: Id[User], addedUsers: Seq[Id[User]], addedNonUsers: Seq[BasicNonUser]) extends ActivityEventData(ActivityKind.AddParticipants)
  implicit val format = Format[ActivityEventData](
    Reads {
      js =>
        (js \ "kind").validate[ActivityKind].flatMap {
          case ActivityKind.AddParticipants => Json.reads[AddParticipants].reads(js)
          case kind => throw new Exception(s"unsupported reads for activity event kind $kind, js $js}")
        }
    },
    Writes {
      case ap: AddParticipants => Json.writes[AddParticipants].writes(ap).as[JsObject] ++ Json.obj("kind" -> ActivityKind.AddParticipants.value)
      case o => throw new Exception(s"unsupported writes for ActivityEventData $o")
    }
  )
}

case class ActivityEvent(
  kind: ActivityKind,
  image: String,
  header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
  body: DescriptionElements, // message and keep.note content
  timestamp: DateTime,
  source: Option[ActivitySource])
object ActivityEvent {

  implicit val writes: Writes[ActivityEvent] = (
    (__ \ 'kind).write[ActivityKind] and
    (__ \ 'image).write[String] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[ActivitySource]
  )(unlift(ActivityEvent.unapply))
}

case class ActivityLog(events: Seq[ActivityEvent], numEvents: Int, numComments: Int)
object ActivityLog {
  val empty = ActivityLog(Seq.empty, numEvents = 0, numComments = 0)

  implicit val writes = new Writes[ActivityLog] {
    def writes(o: ActivityLog) = Json.obj("events" -> o.events, "numEvents" -> o.numEvents, "numComments" -> o.numComments)
  }
}
