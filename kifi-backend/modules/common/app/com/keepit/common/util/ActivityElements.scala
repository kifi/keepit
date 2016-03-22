package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.discussion.Message
import com.keepit.model.{ Organization, Library, User, BasicOrganization, LibraryColor, BasicLibrary }
import com.keepit.social.{ BasicUser, BasicNonUser }
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

sealed abstract class ActivityElement extends DescriptionElement
object ActivityElement {

  case class TextElement(override val text: String) extends ActivityElement {
    val url = None
  }
  object TextElement {
    val kind = "text"
    implicit val writes = new Writes[TextElement] { def writes(t: TextElement) = Json.obj("text" -> t.text, "kind" -> kind) }
  }

  case class UserElement(
      id: ExternalId[User],
      name: String,
      image: String,
      path: Path) extends ActivityElement {
    val text = name
    val url = Some(path.relative)
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
  }
  object NonUserElement {
    val kind = "nonUser"
    implicit val writes = new Writes[NonUserElement] { def writes(n: NonUserElement) = Json.obj("text" -> n.text, "kind" -> kind) }
  }

  case class LibraryElement(
      id: PublicId[Library],
      name: String,
      color: Option[LibraryColor],
      path: Path) extends ActivityElement {
    val text = name
    val url = Some(path.relative)
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
  }
  object OrganizationElement {
    val kind = "organization"
    implicit val writes = new Writes[OrganizationElement] {
      def writes(o: OrganizationElement) = Json.obj("id" -> o.id, "text" -> o.text, "image" -> o.image, "url" -> o.url, "kind" -> kind)
    }
  }

  def fromUser(bu: BasicUser) = UserElement(bu.externalId, bu.fullName, bu.pictureName, Path(bu.username.value))
  def fromNonUser(bnu: BasicNonUser) = NonUserElement(bnu.id)
  def fromBasicLibrary(bl: BasicLibrary) = LibraryElement(bl.id, bl.name, bl.color, Path(bl.path))
  def fromBasicOrg(bo: BasicOrganization) = OrganizationElement(bo.orgId, bo.name, bo.avatarPath.path, bo.path)

  implicit val writes = Writes[ActivityElement] {
    case txt: TextElement => TextElement.writes.writes(txt)
    case usr: UserElement => UserElement.writes.writes(usr)
    case nur: NonUserElement => NonUserElement.writes.writes(nur)
    case lib: LibraryElement => LibraryElement.writes.writes(lib)
    case org: OrganizationElement => OrganizationElement.writes.writes(org)
  }
}

case class ActivityEvent(
  kind: ActivityKind,
  image: String,
  header: Seq[ActivityElement], // e.g. "Cam kept this in LibraryX"
  body: Seq[ActivityElement], // message and keep.note content
  timestamp: DateTime,
  source: Option[ActivitySource])
object ActivityEvent {
  def fromComment(msg: Message): ActivityEvent = {
    val msgAuthor: ActivityElement = msg.sentBy.fold(ActivityElement.fromNonUser, ActivityElement.fromUser)
    ActivityEvent(
      ActivityKind.Comment,
      image = msg.sentBy.right.toOption.map(_.pictureName).getOrElse("0.jpg"),
      header = Seq(msgAuthor, ActivityElement.TextElement("commented on this page")),
      body = Seq(ActivityElement.TextElement(msg.text)),
      timestamp = msg.sentAt,
      source = None // todo(cam): add eliza.message.source (column) to Message
    )
  }

  implicit val writes: Writes[ActivityEvent] = (
    (__ \ 'kind).write[ActivityKind] and
    (__ \ 'image).write[String] and
    (__ \ 'header).write[Seq[ActivityElement]] and
    (__ \ 'body).write[Seq[ActivityElement]] and
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
