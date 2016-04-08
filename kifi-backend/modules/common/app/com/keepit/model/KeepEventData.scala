package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.json.EnumFormat
import com.keepit.common.net.UserAgent
import com.keepit.common.reflection.Enumerator
import com.keepit.common.util.DescriptionElements
import com.keepit.discussion.{ Message, MessageSource }
import com.keepit.social.{ BasicAuthor, BasicNonUser }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

sealed abstract class KeepEventKind(val value: String)
object KeepEventKind extends Enumerator[KeepEventKind] {
  case object Initial extends KeepEventKind("initial")
  case object Comment extends KeepEventKind("comment")
  case object EditTitle extends KeepEventKind("edit_title")
  case object ModifyRecipients extends KeepEventKind("modify_recipients")

  val all = _all
  def contains(str: String) = all.exists(_.value == str)
  def fromStr(str: String) = all.find(_.value == str)
  def apply(str: String) = fromStr(str).get

  implicit val format: Format[KeepEventKind] = EnumFormat.format(fromStr, _.value)
}

@json case class KeepEventSource(kind: KeepEventSourceKind, url: Option[String])

sealed abstract class KeepEventSourceKind(val value: String)
object KeepEventSourceKind extends Enumerator[KeepEventSourceKind] {
  case object Slack extends KeepEventSourceKind("Slack")
  case object Twitter extends KeepEventSourceKind("Twitter")
  case object iOS extends KeepEventSourceKind("iOS")
  case object Android extends KeepEventSourceKind("Android")
  case object Chrome extends KeepEventSourceKind("Chrome") // refers to ext
  case object Firefox extends KeepEventSourceKind("Firefox")
  case object Safari extends KeepEventSourceKind("Safari")
  case object Email extends KeepEventSourceKind("Email")
  case object Site extends KeepEventSourceKind("Kifi.com")

  val all = _all
  def fromStr(str: String) = all.find(_.value == str)
  def apply(str: String) = fromStr(str).get

  implicit val format: Format[KeepEventSourceKind] = EnumFormat.format(fromStr, _.value)

  def fromMessageSource(msgSrc: Option[MessageSource]): Option[KeepEventSourceKind] = msgSrc.flatMap {
    case MessageSource.IPHONE => Some(iOS)
    case src => KeepEventSourceKind.fromStr(src.value)
  }
  def toMessageSource(eventSrc: KeepEventSourceKind): Option[MessageSource] = eventSrc match {
    case KeepEventSourceKind.iOS => Some(MessageSource.IPHONE) // only 8 iPad messages total, all before 2015
    case src => MessageSource.fromStr(src.value)
  }

  def fromUserAgent(userAgent: UserAgent): Option[KeepEventSourceKind] = fromStr(userAgent.name)

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]): QueryStringBindable[KeepEventSourceKind] = new QueryStringBindable[KeepEventSourceKind] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KeepEventSourceKind]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(KeepEventSourceKind(value))
        case _ => Left("Unable to bind a KeepEventSourceKind")
      }
    }

    override def unbind(key: String, source: KeepEventSourceKind): String = {
      stringBinder.unbind(key, source.value)
    }
  }
}

sealed abstract class KeepEventData(val kind: KeepEventKind)
object KeepEventData {
  @json case class ModifyRecipients(addedBy: Id[User], diff: KeepRecipientsDiff) extends KeepEventData(KeepEventKind.ModifyRecipients)
  @json case class EditTitle(editedBy: Id[User], original: Option[String], updated: Option[String]) extends KeepEventData(KeepEventKind.EditTitle)
  implicit val diffFormat = KeepRecipientsDiff.internalFormat
  implicit val format = Format[KeepEventData](
    Reads {
      js =>
        (js \ "kind").validate[KeepEventKind].flatMap {
          case KeepEventKind.EditTitle => Json.reads[EditTitle].reads(js)
          case KeepEventKind.ModifyRecipients => Json.reads[ModifyRecipients].reads(js)
          case KeepEventKind.Initial | KeepEventKind.Comment => throw new Exception(s"unsupported reads for activity event kind, js $js}")
        }
    },
    Writes {
      case et: EditTitle => Json.writes[EditTitle].writes(et).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.EditTitle.value)
      case ar: ModifyRecipients => Json.writes[ModifyRecipients].writes(ar).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.ModifyRecipients.value)
      case o => throw new Exception(s"unsupported writes for ActivityEventData $o")
    }
  )
}

case class BasicKeepEvent(
    id: Option[PublicId[Message]],
    author: BasicAuthor,
    kind: KeepEventKind,
    header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
    body: DescriptionElements, // message and keep.note content
    timestamp: DateTime,
    source: Option[KeepEventSource]) {

  def withHeader(newHeader: DescriptionElements) = this.copy(header = newHeader)
}
object BasicKeepEvent {
  implicit val writes: Writes[BasicKeepEvent] = (
    (__ \ 'id).writeNullable[PublicId[Message]] and
    (__ \ 'author).write[BasicAuthor] and
    (__ \ 'kind).write[KeepEventKind] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[KeepEventSource]
  )(unlift(BasicKeepEvent.unapply))

  def generateCommentEvent(id: PublicId[Message], author: BasicAuthor, text: String, sentAt: DateTime, source: Option[MessageSource]): BasicKeepEvent = {
    BasicKeepEvent(
      id = Some(id),
      author = author,
      kind = KeepEventKind.Comment,
      header = DescriptionElements(author.name, "commented on this page"),
      body = text,
      timestamp = sentAt,
      source = KeepEventSourceKind.fromMessageSource(source).map(KeepEventSource(_, url = None))
    )
  }
}

case class KeepActivity(latestEvent: BasicKeepEvent, events: Seq[BasicKeepEvent], numComments: Int)
object KeepActivity {
  implicit val writes = new Writes[KeepActivity] {
    def writes(o: KeepActivity) = Json.obj("latestEvent" -> o.latestEvent, "events" -> o.events, "numComments" -> o.numComments)
  }
}
