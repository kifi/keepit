package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ PublicIdGenerator, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.json.{ SchemaReads, EitherFormat, EnumFormat }
import com.keepit.common.net.UserAgent
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.DescriptionElements
import com.keepit.common.util.DescriptionElements._
import com.keepit.discussion.{ Message, MessageSource }
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.social.BasicAuthor
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

sealed abstract class KeepEventKind(val value: String)
object KeepEventKind extends Enumerator[KeepEventKind] {
  case object Initial extends KeepEventKind("initial")
  case object Note extends KeepEventKind("note")
  case object Comment extends KeepEventKind("comment")
  case object EditTitle extends KeepEventKind("edit_title")
  case object ModifyRecipients extends KeepEventKind("modify_recipients")

  val all = _all
  def fromStr(str: String) = all.find(_.value == str)
  def apply(str: String) = fromStr(str).getOrElse(throw new Exception(s"Invalid KeepEventKind: $str"))

  implicit val format: Format[KeepEventKind] = EnumFormat.format(fromStr, _.value)
  val hideForNow: Set[KeepEventKind] = Set(EditTitle)
}

@json case class BasicKeepEventSource(kind: KeepEventSource, url: Option[String])
object BasicKeepEventSource {
  def fromSourceAttribution(attribution: SourceAttribution): Option[BasicKeepEventSource] = {
    attribution match {
      case SlackAttribution(message, _) => Some(BasicKeepEventSource(KeepEventSource.Slack, Some(message.permalink)))
      case TwitterAttribution(tweet) => Some(BasicKeepEventSource(KeepEventSource.Twitter, Some(tweet.permalink)))
      case ka: KifiAttribution => KeepEventSource.fromKeepSource(ka.source).map(src => BasicKeepEventSource(src, None))
    }
  }
}

sealed abstract class KeepEventSource(val value: String)
object KeepEventSource extends Enumerator[KeepEventSource] {
  case object Slack extends KeepEventSource("Slack")
  case object Twitter extends KeepEventSource("Twitter")
  case object iOS extends KeepEventSource("iOS")
  case object Android extends KeepEventSource("Android")
  case object Extension extends KeepEventSource("the extension")
  case object Chrome extends KeepEventSource("Chrome")
  case object Firefox extends KeepEventSource("Firefox")
  case object Safari extends KeepEventSource("Safari")
  case object Email extends KeepEventSource("Email")
  case object Site extends KeepEventSource("Kifi.com")

  val all = _all
  def fromStr(str: String) = all.find(_.value == str)
  def apply(str: String) = fromStr(str).get

  implicit val format: Format[KeepEventSource] = EnumFormat.format(fromStr, _.value)
  implicit val schemaReads: SchemaReads[KeepEventSource] = SchemaReads.trivial("keep_event_source")

  def fromMessageSource(msgSrc: Option[MessageSource]): Option[KeepEventSource] = msgSrc.flatMap {
    case MessageSource.IPHONE => Some(iOS)
    case src => KeepEventSource.fromStr(src.value)
  }
  def toMessageSource(eventSrc: KeepEventSource): Option[MessageSource] = eventSrc match {
    case KeepEventSource.iOS => Some(MessageSource.IPHONE) // only 8 iPad messages total, all before 2015
    case src => MessageSource.fromStr(src.value)
  }

  def fromKeepSource(keepSrc: KeepSource): Option[KeepEventSource] = keepSrc match {
    case KeepSource.keeper => Some(KeepEventSource.Extension)
    case KeepSource.site => Some(KeepEventSource.Site)
    case KeepSource.email => Some(KeepEventSource.Email)
    case KeepSource.slack => Some(KeepEventSource.Slack)
    case KeepSource.twitterFileImport | KeepSource.twitterSync => Some(KeepEventSource.Twitter)
    case _ => None
  }

  def fromUserAgent(userAgent: UserAgent): Option[KeepEventSource] = fromStr(userAgent.name)

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]): QueryStringBindable[KeepEventSource] = new QueryStringBindable[KeepEventSource] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KeepEventSource]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(KeepEventSource(value))
        case _ => Left("Unable to bind a KeepEventSourceKind")
      }
    }

    override def unbind(key: String, source: KeepEventSource): String = {
      stringBinder.unbind(key, source.value)
    }
  }
}

sealed abstract class KeepEventData(val kind: KeepEventKind)
object KeepEventData {
  implicit val diffFormat = KeepRecipientsDiff.internalFormat
  @json case class ModifyRecipients(addedBy: Id[User], diff: KeepRecipientsDiff) extends KeepEventData(KeepEventKind.ModifyRecipients)
  @json case class EditTitle(editedBy: Id[User], original: Option[String], updated: Option[String]) extends KeepEventData(KeepEventKind.EditTitle)
  implicit val format = Format[KeepEventData](
    Reads {
      js =>
        (js \ "kind").validate[KeepEventKind].flatMap {
          case KeepEventKind.EditTitle => Json.reads[EditTitle].reads(js)
          case KeepEventKind.ModifyRecipients => Json.reads[ModifyRecipients].reads(js)
          case KeepEventKind.Initial | KeepEventKind.Note | KeepEventKind.Comment => throw new Exception(s"unsupported reads for activity event kind, js $js}")
        }
    },
    Writes {
      case et: EditTitle => Json.writes[EditTitle].writes(et).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.EditTitle.value)
      case ar: ModifyRecipients => Json.writes[ModifyRecipients].writes(ar).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.ModifyRecipients.value)
      case o => throw new Exception(s"unsupported writes for ActivityEventData $o")
    }
  )
}

case class CommonKeepEvent()
object CommonKeepEvent extends PublicIdGenerator[CommonKeepEvent] {
  val publicIdIvSpec: IvParameterSpec = new IvParameterSpec(Array(125, 15, 73, -1, 17, -36, 81, -84, -126, 92, 65, -97, 127, 47, -113, 58))
  val publicIdPrefix = "kev"
}

//case class BasicKeepEventId(id: Option[Either[PublicId[Message], PublicId[CommonKeepEvent]]])
//object BasicKeepEventId {
//  def fromId(id: PublicId[])
//}

case class BasicKeepEvent(
    id: BasicKeepEventId,
    author: BasicAuthor,
    kind: KeepEventKind,
    header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
    body: DescriptionElements, // message and keep.note content
    timestamp: DateTime,
    source: Option[BasicKeepEventSource]) {

  def withHeader(newHeader: DescriptionElements) = this.copy(header = newHeader)
}

object BasicKeepEvent {
  sealed abstract class BasicKeepEventId
  object BasicKeepEventId {
    final case class MessageId(id: PublicId[Message]) extends BasicKeepEventId
    final case class EventId(id: PublicId[CommonKeepEvent]) extends BasicKeepEventId
    final case class InitialId(id: PublicId[Keep]) extends BasicKeepEventId
    final case class NoteId(id: PublicId[Keep]) extends BasicKeepEventId
    implicit val writes: Writes[BasicKeepEventId] = Writes {
      case InitialId(kId) => JsString(s"init_${kId.id}")
      case NoteId(kId) => JsString(s"note_${kId.id}")
      case MessageId(msgId) => JsString(msgId.id)
      case EventId(eventId) => JsString(eventId.id)
    }
    private val reads: Reads[BasicKeepEventId] = Reads(js => Stream(
      CommonKeepEvent.readsPublicId.reads(js).map(EventId(_)),
      Message.readsPublicId.reads(js).map(MessageId(_)),
      js.validate[String].filter(_.startsWith("init_")).flatMap(s => Keep.readsPublicId.reads(JsString(s.stripPrefix("init_"))).map(InitialId(_))),
      js.validate[String].filter(_.startsWith("note_")).flatMap(s => Keep.readsPublicId.reads(JsString(s.stripPrefix("note_"))).map(NoteId(_)))
    ).reduce(_ orElse _))

    def fromMsg(id: PublicId[Message]) = MessageId(id)
    def fromEvent(id: PublicId[CommonKeepEvent]) = EventId(id)
  }

  implicit val idFormat = EitherFormat(Message.formatPublicId, CommonKeepEvent.formatPublicId)
  implicit val writes: Writes[BasicKeepEvent] = (
    (__ \ 'id).write[BasicKeepEventId] and
    (__ \ 'author).write[BasicAuthor] and
    (__ \ 'kind).write[KeepEventKind] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[BasicKeepEventSource]
  )(unlift(BasicKeepEvent.unapply))

  def fromMessage(message: Message)(implicit imageConfig: S3ImageConfig): BasicKeepEvent = {
    val author = message.sentBy.fold(BasicAuthor.fromNonUser, BasicAuthor.fromUser)
    generateCommentEvent(message.pubId, author, message.text, message.sentAt, message.source)
  }

  def generateCommentEvent(id: PublicId[Message], author: BasicAuthor, text: String, sentAt: DateTime, source: Option[MessageSource]): BasicKeepEvent = {
    BasicKeepEvent(
      id = BasicKeepEventId.fromMsg(id),
      author = author,
      kind = KeepEventKind.Comment,
      header = DescriptionElements(author),
      body = text,
      timestamp = sentAt,
      source = KeepEventSource.fromMessageSource(source).map(BasicKeepEventSource(_, url = None))
    )
  }
}

case class KeepActivity(latestEvent: BasicKeepEvent, events: Seq[BasicKeepEvent], numComments: Int)
object KeepActivity {
  implicit val writes = new Writes[KeepActivity] {
    def writes(o: KeepActivity) = Json.obj("latestEvent" -> o.latestEvent, "events" -> o.events, "numComments" -> o.numComments)
  }
}
