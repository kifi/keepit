package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.json.EnumFormat
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.DescriptionElements
import com.keepit.discussion.{ Message, MessageSource }
import com.keepit.social.{ NonUserKinds, BasicAuthor, BasicUser, ImageUrls, BasicNonUser }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class KeepEventKind(val value: String)
object KeepEventKind extends Enumerator[KeepEventKind] {
  case object Initial extends KeepEventKind("initial")
  case object Comment extends KeepEventKind("comment")
  case object AddParticipants extends KeepEventKind("add_participants")

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

  def fromMessageSource(msgSrc: Option[MessageSource]): Option[KeepEventSourceKind] = msgSrc.flatMap { src =>
    src match {
      case MessageSource.IPAD | MessageSource.IPHONE => Some(iOS)
      case MessageSource.CHROME | MessageSource.FIREFOX | MessageSource.SAFARI |
        MessageSource.ANDROID | MessageSource.EMAIL | MessageSource.SITE => Some(KeepEventSourceKind.apply(src.value))
      case _ => None
    }
  }
}

sealed abstract class KeepEvent(val kind: KeepEventKind)
object KeepEvent {
  @json case class AddParticipants(addedBy: Id[User], addedUsers: Seq[Id[User]], addedNonUsers: Seq[BasicNonUser]) extends KeepEvent(KeepEventKind.AddParticipants)
  @json case class InitialKeep(addedBy: Option[Id[User]], addedUsers: Set[Id[User]], addedNonUsers: Set[BasicNonUser], addedLibraries: Set[Id[Library]]) extends KeepEvent(KeepEventKind.Initial)
  implicit val format = Format[KeepEvent](
    Reads {
      js =>
        (js \ "kind").validate[KeepEventKind].flatMap {
          case KeepEventKind.AddParticipants => Json.reads[AddParticipants].reads(js)
          case KeepEventKind.Initial => Json.reads[InitialKeep].reads(js)
          case kind => throw new Exception(s"unsupported reads for activity event kind $kind, js $js}")
        }
    },
    Writes {
      case ap: AddParticipants => Json.writes[AddParticipants].writes(ap).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.AddParticipants.value)
      case i: InitialKeep => Json.writes[InitialKeep].writes(i).as[JsObject] ++ Json.obj("kind" -> KeepEventKind.Initial.value)
      case o => throw new Exception(s"unsupported writes for KeepEvent $o")
    }
  )
}

sealed abstract class KeepEventAuthor(val kind: String) {
  val image: String
  val url: Option[String]
}
object KeepEventAuthor {
  import com.keepit.common.core._
  case class User(id: String, override val image: String, url: Option[String]) extends KeepEventAuthor("user")
  object User {
    implicit val writes: Writes[User] = Writes { o => Json.obj("kind" -> o.kind, "id" -> o.id, "image" -> o.image, "url" -> o.url).nonNullFields }
  }

  case class Slack(url: Option[String]) extends KeepEventAuthor("slack") {
    val image = ImageUrls.SLACK_LOGO
  }
  object Slack {
    implicit val writes: Writes[Slack] = Writes { o => Json.obj("kind" -> o.kind, "image" -> o.image, "url" -> o.url).nonNullFields }
  }

  case class Twitter(url: Option[String]) extends KeepEventAuthor("twitter") {
    val image = ImageUrls.TWITTER_LOGO
  }
  object Twitter {
    implicit val writes: Writes[Twitter] = Writes { o => Json.obj("kind" -> o.kind, "image" -> o.image, "url" -> o.url).nonNullFields }
  }

  case class Email(email: String) extends KeepEventAuthor("email") {
    val image = BasicUser.defaultImageForEmail(email)
    val url = None
  }
  object Email {
    implicit val writes: Writes[Email] = Writes { o => Json.obj("kind" -> o.kind, "image" -> o.image, "email" -> o.email) }
  }

  def fromBasicUser(bu: BasicUser)(implicit imageConfig: S3ImageConfig): KeepEventAuthor = User(bu.externalId.id, bu.picturePath.getUrl, Some(Path(bu.username.value).absolute))

  def fromBasicAuthor(ba: BasicAuthor): KeepEventAuthor = ba match {
    case k: BasicAuthor.KifiUser => User(k.id, k.picture, Some(k.url))
    case s: BasicAuthor.SlackUser => Slack(Some(s.url))
    case t: BasicAuthor.TwitterUser => Twitter(Some(t.url))
  }

  def fromBasicNonUser(bn: BasicNonUser): KeepEventAuthor = bn.kind match {
    case NonUserKinds.email => Email(bn.id)
  }

  implicit def writes: Writes[KeepEventAuthor] = Writes {
    case o: User => User.writes.writes(o)
    case o: Slack => Slack.writes.writes(o)
    case o: Twitter => Twitter.writes.writes(o)
    case o: Email => Email.writes.writes(o)
  }
}

case class BasicKeepEvent(
  id: Option[PublicId[Message]],
  author: KeepEventAuthor,
  kind: KeepEventKind,
  header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
  body: DescriptionElements, // message and keep.note content
  timestamp: DateTime,
  source: Option[KeepEventSource])
object BasicKeepEvent {
  implicit val writes: Writes[BasicKeepEvent] = (
    (__ \ 'id).writeNullable[PublicId[Message]] and
    (__ \ 'author).write[KeepEventAuthor] and
    (__ \ 'kind).write[KeepEventKind] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[KeepEventSource]
  )(unlift(BasicKeepEvent.unapply))
}

case class KeepActivity(events: Seq[BasicKeepEvent], numComments: Int)
object KeepActivity {
  val empty = KeepActivity(Seq.empty, numComments = 0)

  implicit val writes = new Writes[KeepActivity] {
    def writes(o: KeepActivity) = Json.obj("events" -> o.events, "numComments" -> o.numComments)
  }
}
