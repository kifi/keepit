package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicIdGenerator, PublicId }
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

sealed abstract class KeepEventDisplayStyle(val value: String)
object KeepEventDisplayStyle extends Enumerator[KeepEventDisplayStyle] {
  case object Primary extends KeepEventDisplayStyle("primary")
  case object GreyedOut extends KeepEventDisplayStyle("greyed_out")

  def fromKind(kind: KeepEventKind): KeepEventDisplayStyle = kind match {
    case KeepEventKind.Note | KeepEventKind.Comment => Primary
    case KeepEventKind.Initial | KeepEventKind.EditTitle | KeepEventKind.ModifyRecipients => GreyedOut
  }
  implicit val writes: Writes[KeepEventDisplayStyle] = Writes { o => JsString(o.value) }
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
  case object iPhone extends KeepEventSource("iPhone")
  case object Android extends KeepEventSource("Android")
  case object Extension extends KeepEventSource("the extension")
  case object Chrome extends KeepEventSource("Chrome")
  case object Firefox extends KeepEventSource("Firefox")
  case object Safari extends KeepEventSource("Safari")
  case object Email extends KeepEventSource("Email")
  case object Site extends KeepEventSource("Kifi.com")

  // deprecated, use iPhone instead
  case object iOS extends KeepEventSource("iOS")

  val all = _all
  def fromStr(str: String) = all.find(_.value.toLowerCase == str.toLowerCase)
  def apply(str: String) = fromStr(str).get

  implicit val format: Format[KeepEventSource] = EnumFormat.format(fromStr, _.value)
  implicit val schemaReads: SchemaReads[KeepEventSource] = SchemaReads.trivial("keep_event_source")

  def fromMessageSource(msgSrc: MessageSource): Option[KeepEventSource] = KeepEventSource.fromStr(msgSrc.value)
  def toMessageSource(eventSrc: KeepEventSource): Option[MessageSource] = MessageSource.fromStr(eventSrc.value)

  def fromKeepSource(keepSrc: KeepSource): Option[KeepEventSource] = keepSrc match {
    case KeepSource.Keeper => Some(KeepEventSource.Extension)
    case KeepSource.Site => Some(KeepEventSource.Site)
    case KeepSource.Email => Some(KeepEventSource.Email)
    case KeepSource.Slack => Some(KeepEventSource.Slack)
    case KeepSource.TwitterFileImport | KeepSource.TwitterSync => Some(KeepEventSource.Twitter)
    case src => KeepEventSource.fromStr(src.value)
  }

  def toKeepSource(eventSrc: KeepEventSource): Option[KeepSource] = eventSrc match {
    case KeepEventSource.Site => Some(KeepSource.Site)
    case KeepEventSource.Twitter => Some(KeepSource.TwitterSync) // many to one from KeepSource -> KeepEventSource, so this is the lossy choice
    case KeepEventSource.Extension => Some(KeepSource.Keeper)
    case source => KeepSource.fromStr(source.value)
  }

  def fromUserAgent(agent: UserAgent): Option[KeepEventSource] = {
    if (agent.isKifiIphoneApp) Some(iPhone)
    else if (agent.isKifiAndroidApp) Some(Android)
    else KeepEventSource.fromStr(agent.name)
  }
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

sealed abstract class KeepEventData(val kind: KeepEventKind) {
  def isValid: Boolean
}
object KeepEventData {
  implicit val diffFormat = KeepRecipientsDiff.internalFormat
  @json case class ModifyRecipients(addedBy: Id[User], diff: KeepRecipientsDiff) extends KeepEventData(KeepEventKind.ModifyRecipients) {
    def isValid = diff.nonEmpty
  }
  @json case class EditTitle(editedBy: Id[User], original: Option[String], updated: Option[String]) extends KeepEventData(KeepEventKind.EditTitle) {
    def isValid = original != updated
  }
  implicit val format = Format[KeepEventData](
    Reads {
      js =>
        (js \ "kind").validate[KeepEventKind].flatMap {
          case KeepEventKind.EditTitle => Json.fromJson[EditTitle](js)
          case KeepEventKind.ModifyRecipients => Json.fromJson[ModifyRecipients](js)
          case KeepEventKind.Initial | KeepEventKind.Note | KeepEventKind.Comment => throw new Exception(s"unsupported reads for activity event kind, js $js}")
        }
    },
    Writes {
      case et: EditTitle => Json.toJson[EditTitle](et).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.EditTitle.value)
      case ar: ModifyRecipients => Json.toJson[ModifyRecipients](ar).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.ModifyRecipients.value)
      case o => throw new Exception(s"unsupported writes for ActivityEventData $o")
    }
  )
}

case class CommonKeepEvent(
  id: Id[CommonKeepEvent],
  keepId: Id[Keep],
  timestamp: DateTime,
  eventData: KeepEventData,
  source: Option[KeepEventSource])
object CommonKeepEvent extends PublicIdGenerator[CommonKeepEvent] {
  val publicIdIvSpec: IvParameterSpec = new IvParameterSpec(Array(125, 15, 73, -1, 17, -36, 81, -84, -126, 92, 65, -97, 127, 47, -113, 58))
  val publicIdPrefix = "kev"
  val format: Format[CommonKeepEvent] = (
    (__ \ 'id).format[Id[CommonKeepEvent]] and
    (__ \ 'keepId).format[Id[Keep]] and
    (__ \ 'timestamp).format[DateTime] and
    (__ \ 'eventData).format[KeepEventData] and
    (__ \ 'source).formatNullable[KeepEventSource]
  )(CommonKeepEvent.apply, unlift(CommonKeepEvent.unapply))
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
    private val writes: Writes[BasicKeepEventId] = Writes {
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

    implicit val format: Format[BasicKeepEventId] = Format(reads, writes)

    def fromMsg(id: Id[Message])(implicit config: PublicIdConfiguration) = MessageId(Message.publicId(id))
    def fromEvent(id: Id[CommonKeepEvent])(implicit config: PublicIdConfiguration) = EventId(CommonKeepEvent.publicId(id))
    def fromPubMsg(id: PublicId[Message]) = MessageId(id)
    def fromPubEvent(id: PublicId[CommonKeepEvent]) = EventId(id)
  }

  implicit val idFormat = EitherFormat(Message.formatPublicId, CommonKeepEvent.formatPublicId)

  private val bareWrites: OWrites[BasicKeepEvent] = (
    (__ \ 'id).write[BasicKeepEventId] and
    (__ \ 'author).write[BasicAuthor] and
    (__ \ 'kind).write[KeepEventKind] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[BasicKeepEventSource]
  )(unlift(BasicKeepEvent.unapply))

  private val extraWrites: OWrites[BasicKeepEvent] = OWrites { o =>
    Json.obj(
      "displayStyle" -> KeepEventDisplayStyle.fromKind(o.kind)
    )
  }

  implicit val writes: OWrites[BasicKeepEvent] = OWrites { o => bareWrites.writes(o) ++ extraWrites.writes(o) }

  val format: Format[BasicKeepEvent] = (
    (__ \ 'id).format[BasicKeepEventId](BasicKeepEventId.format) and
    (__ \ 'author).format[BasicAuthor] and
    (__ \ 'kind).format[KeepEventKind] and
    (__ \ 'header).format[DescriptionElements](DescriptionElements.format) and
    (__ \ 'body).format[DescriptionElements](DescriptionElements.format) and
    (__ \ 'timestamp).format[DateTime] and
    (__ \ 'source).formatNullable[BasicKeepEventSource]
  )(BasicKeepEvent.apply, unlift(BasicKeepEvent.unapply))

  def fromMessage(message: Message)(implicit imageConfig: S3ImageConfig): BasicKeepEvent = {
    val author = message.sentBy.fold(BasicAuthor.fromNonUser, BasicAuthor.fromUser)
    generateCommentEvent(message.pubId, author, message.text, message.sentAt, message.source)
  }

  def generateCommentEvent(id: PublicId[Message], author: BasicAuthor, text: String, sentAt: DateTime, source: Option[MessageSource]): BasicKeepEvent = {
    BasicKeepEvent(
      id = BasicKeepEventId.fromPubMsg(id),
      author = author,
      kind = KeepEventKind.Comment,
      header = DescriptionElements(author),
      body = text,
      timestamp = sentAt,
      source = source.flatMap(KeepEventSource.fromMessageSource).map(BasicKeepEventSource(_, url = None))
    )
  }
}

@json case class CommonAndBasicKeepEvent(commonEvent: CommonKeepEvent, basicEvent: BasicKeepEvent)
object CommonAndBasicKeepEvent {
  implicit val basicEventFormat = BasicKeepEvent.format
  implicit val commonEventFormat = CommonKeepEvent.format
}

case class KeepActivity(latestEvent: BasicKeepEvent, events: Seq[BasicKeepEvent], numComments: Int)
object KeepActivity {
  implicit val writes = new Writes[KeepActivity] {
    def writes(o: KeepActivity) = Json.obj("latestEvent" -> o.latestEvent, "events" -> o.events, "numComments" -> o.numComments)
  }
}
