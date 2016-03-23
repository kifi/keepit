package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.core._
import com.keepit.discussion.Message
import com.keepit.model.{ Organization, Library, User, BasicOrganization, LibraryColor, BasicLibrary }
import com.keepit.social.BasicAuthor.{ TwitterUser, SlackUser, KifiUser }
import com.keepit.social.{ BasicAuthor, BasicUser, BasicNonUser }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class ActivityKind(val value: String)
object ActivityKind extends Enumerator[ActivityKind] {
  case object Initial extends ActivityKind("initial")
  case object Comment extends ActivityKind("comment")
  case object AddedParticipants extends ActivityKind("added_participants")
  case object AddedLibrary extends ActivityKind("added_library")
  case object EditedTitle extends ActivityKind("edited_title")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get

  implicit val writes: Writes[ActivityKind] = Writes { o => JsString(o.value) }
}

sealed abstract class ActivitySource(val value: String)
object ActivitySource extends Enumerator[ActivitySource] {
  case object Slack extends ActivitySource("Slack")
  case object Twitter extends ActivitySource("Twitter")
  case object iOS extends ActivitySource("iOS")
  case object Android extends ActivitySource("Android")
  case object Chrome extends ActivitySource("Chrome")
  case object Firefox extends ActivitySource("Firefox")
  case object Safari extends ActivitySource("Safari")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get

  implicit val writes: Writes[ActivitySource] = Writes { o => JsString(o.value) }
}

trait ActivityElement extends DescriptionElement
object ActivityElement {
  case class ImageElement(override val url: Option[String], image: String) extends ActivityElement {
    val text = ""
    def flatten = Seq(this)
  }
  object ImageElement {
    val kind = "image"
    implicit val writes = new Writes[ImageElement] { def writes(i: ImageElement) = Json.obj("text" -> i.text, "image" -> i.image, "kind" -> kind) }
  }

  case class UserElement(
      id: ExternalId[User],
      name: String,
      image: String,
      path: Path) extends ActivityElement {
    val text = name
    val url = Some(path.relative)
    def flatten = Seq(this)
  }
  object UserElement {
    val kind = "user"
    implicit val writes = new Writes[UserElement] {
      def writes(u: UserElement) = Json.obj("id" -> u.id, "text" -> u.text, "image" -> u.image, "url" -> u.url, "kind" -> kind)
    }
  }

  case class NonUserElement(id: String) extends ActivityElement {
    val text = id
    val url = None
    def flatten = Seq(this)
  }
  object NonUserElement {
    val kind = "nonUser"
    implicit val writes = new Writes[NonUserElement] { def writes(n: NonUserElement) = Json.obj("text" -> n.text, "kind" -> kind) }
  }

  case class AuthorElement(
      id: String,
      name: String,
      image: String,
      override val url: Option[String]) extends ActivityElement {
    val text = name
    def flatten = Seq(this)
  }
  object AuthorElement {
    val kind = "author"
    implicit val writes = new Writes[AuthorElement] {
      def writes(a: AuthorElement) = Json.obj("id" -> a.id, "text" -> a.text, "image" -> a.image, "url" -> a.url, "kind" -> kind).nonNullFields
    }
  }

  case class LibraryElement(
      id: PublicId[Library],
      name: String,
      color: Option[LibraryColor],
      path: Path) extends ActivityElement {
    val text = name
    val url = Some(path.relative)
    def flatten = Seq(this)
  }
  object LibraryElement {
    val kind = "library"
    implicit val writes = new Writes[LibraryElement] {
      def writes(l: LibraryElement) = Json.obj("id" -> l.id, "text" -> l.text, "image" -> l.color, "url" -> l.url, "kind" -> kind)
    }
  }

  case class OrganizationElement(
      id: PublicId[Organization],
      name: String,
      image: String,
      path: Path) extends ActivityElement {
    val text = name
    val url = Some(path.relative)
    def flatten = Seq(this)
  }
  object OrganizationElement {
    val kind = "organization"
    implicit val writes = new Writes[OrganizationElement] {
      def writes(o: OrganizationElement) = Json.obj("id" -> o.id, "text" -> o.text, "image" -> o.image, "url" -> o.url, "kind" -> kind)
    }
  }

  implicit def fromUser(bu: BasicUser): UserElement = UserElement(bu.externalId, bu.fullName, bu.pictureName, Path(bu.username.value))
  implicit def fromBasicOrg(bo: BasicOrganization): OrganizationElement = OrganizationElement(bo.orgId, bo.name, bo.avatarPath.path, bo.path)

  implicit def fromNonUser(bnu: BasicNonUser): NonUserElement = NonUserElement(bnu.id)
  implicit def fromBasicAuthor(ba: BasicAuthor): AuthorElement = ba match {
    case KifiUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url))
    case SlackUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url))
    case TwitterUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url))
  }
  implicit def fromBasicLibrary(bl: BasicLibrary): LibraryElement = LibraryElement(bl.id, bl.name, bl.color, Path(bl.path))

  implicit val writes = Writes[ActivityElement] {
    case usr: UserElement => UserElement.writes.writes(usr)
    case nur: NonUserElement => NonUserElement.writes.writes(nur)
    case aut: AuthorElement => AuthorElement.writes.writes(aut)
    case lib: LibraryElement => LibraryElement.writes.writes(lib)
    case org: OrganizationElement => OrganizationElement.writes.writes(org)
  }
}

case class ActivityEvent(
  kind: ActivityKind,
  image: String,
  header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
  body: DescriptionElements, // message and keep.note content
  timestamp: DateTime,
  source: Option[ActivitySource])
object ActivityEvent {
  def fromComment(msg: Message)(implicit iamgeConfig: S3ImageConfig): ActivityEvent = {
    import com.keepit.common.util.ActivityElement._
    val msgAuthor: ActivityElement = msg.sentBy.fold(fromNonUser, fromUser)
    ActivityEvent(
      ActivityKind.Comment,
      image = msg.sentBy.right.toOption.map(_.picturePath.getUrl).getOrElse("0.jpg"),
      header = DescriptionElements(msgAuthor, "commented on this page"),
      body = DescriptionElements(msg.text),
      timestamp = msg.sentAt,
      source = None // todo(cam): add eliza.message.source (column) to Message
    )
  }

  implicit val writes: Writes[ActivityEvent] = (
    (__ \ 'kind).write[ActivityKind] and
    (__ \ 'image).write[String] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[ActivitySource]
  )(unlift(ActivityEvent.unapply))
}

case class ActivityLog(events: Seq[ActivityEvent])
object ActivityLog {
  val empty = ActivityLog(Seq.empty)

  implicit val writes = new Writes[ActivityLog] {
    def writes(o: ActivityLog) = Json.obj("events" -> o.events)
  }
}
