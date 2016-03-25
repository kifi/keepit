package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.json.EnumFormat
import com.keepit.common.reflection.Enumerator
import com.keepit.common.util.DescriptionElements
import com.keepit.discussion.MessageSource
import com.keepit.social.BasicNonUser
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class KeepEventKind(val value: String)
object KeepEventKind extends Enumerator[KeepEventKind] {
  case object Initial extends KeepEventKind("initial")
  case object Comment extends KeepEventKind("comment")
  case object AddParticipants extends KeepEventKind("add_participants")
  case object AddedLibrary extends KeepEventKind("added_library")
  case object EditedTitle extends KeepEventKind("edited_title")

  val all = _all
  def contains(str: String) = all.exists(_.value == str)
  def fromStr(str: String) = all.find(_.value == str)
  def apply(str: String) = fromStr(str).get

  implicit val format: Format[KeepEventKind] = EnumFormat.format(fromStr, _.value)
}

sealed abstract class KeepEventSource(val value: String)
object KeepEventSource extends Enumerator[KeepEventSource] {
  case object Slack extends KeepEventSource("Slack")
  case object Twitter extends KeepEventSource("Twitter")
  case object iOS extends KeepEventSource("iOS")
  case object Android extends KeepEventSource("Android")
  case object Chrome extends KeepEventSource("Chrome") // refers to ext
  case object Firefox extends KeepEventSource("Firefox")
  case object Safari extends KeepEventSource("Safari")
  case object Email extends KeepEventSource("Email")
  case object Site extends KeepEventSource("Kifi.com")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get

  implicit val writes: Writes[KeepEventSource] = Writes { o => JsString(o.value) }

  def fromMessageSource(msgSrc: Option[MessageSource]): Option[KeepEventSource] = msgSrc.flatMap { src =>
    src match {
      case MessageSource.IPAD | MessageSource.IPHONE => Some(iOS)
      case MessageSource.CHROME | MessageSource.FIREFOX | MessageSource.SAFARI |
        MessageSource.ANDROID | MessageSource.EMAIL | MessageSource.SITE => Some(KeepEventSource.apply(src.value))
      case _ => None
    }
  }
}

sealed abstract class KeepEvent(val kind: KeepEventKind)
object KeepEvent {
  @json case class AddParticipants(addedBy: Id[User], addedUsers: Seq[Id[User]], addedNonUsers: Seq[BasicNonUser]) extends KeepEvent(KeepEventKind.AddParticipants)
  implicit val format = Format[KeepEvent](
    Reads {
      js =>
        (js \ "kind").validate[KeepEventKind].flatMap {
          case KeepEventKind.AddParticipants => Json.reads[AddParticipants].reads(js)
          case kind => throw new Exception(s"unsupported reads for activity event kind $kind, js $js}")
        }
    },
    Writes {
      case ap: AddParticipants => Json.writes[AddParticipants].writes(ap).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.AddParticipants.value)
      case o => throw new Exception(s"unsupported writes for ActivityEventData $o")
    }
  )
}

case class BasicActivityEvent(
  kind: KeepEventKind,
  image: String,
  header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
  body: DescriptionElements, // message and keep.note content
  timestamp: DateTime,
  source: Option[KeepEventSource])
object BasicActivityEvent {

  implicit val writes: Writes[BasicActivityEvent] = (
    (__ \ 'kind).write[KeepEventKind] and
    (__ \ 'image).write[String] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[KeepEventSource]
  )(unlift(BasicActivityEvent.unapply))
}

case class KeepActivity(events: Seq[BasicActivityEvent], numEvents: Int, numComments: Int)
object KeepActivity {
  val empty = KeepActivity(Seq.empty, numEvents = 0, numComments = 0)

  implicit val writes = new Writes[KeepActivity] {
    def writes(o: KeepActivity) = Json.obj("events" -> o.events, "numEvents" -> o.numEvents, "numComments" -> o.numComments)
  }
}
