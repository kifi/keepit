package com.keepit.common.util

import com.keepit.common.core.jsObjectExtensionOps
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json.EnumFormat
import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.strings.StringWithReplacements
import com.keepit.macros.Location
import com.keepit.model.{ BasicLibrary, BasicOrganization, Library, LibraryColor, Organization, OrganizationRole, User }
import com.keepit.slack.models.{ SlackEmoji, SlackUsername }
import com.keepit.social.BasicAuthor.{ EmailUser, KifiUser, SlackUser, TwitterUser }
import com.keepit.social.{ AuthorKind, BasicAuthor, BasicNonUser, BasicUser }
import org.joda.time.{ DateTime, Duration }
import org.ocpsoft.prettytime.PrettyTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.twirl.api.Html

sealed abstract class DescriptionElementKind(val value: String)
object DescriptionElementKind extends Enumerator[DescriptionElementKind] {
  case object Text extends DescriptionElementKind("text")
  case object Image extends DescriptionElementKind("image")
  case object ShowOriginal extends DescriptionElementKind("showOriginal")
  case object User extends DescriptionElementKind("user")
  case object NonUser extends DescriptionElementKind("nonUser")
  case object Author extends DescriptionElementKind("author")
  case object Library extends DescriptionElementKind("library")
  case object Organization extends DescriptionElementKind("organization")

  val all = _all
  def apply(str: String): DescriptionElementKind = all.find(_.value == str).get
  def fromStr(str: String): Option[DescriptionElementKind] = all.find(_.value == str)

  implicit val format: Format[DescriptionElementKind] = EnumFormat.format(fromStr, _.value, domain = all.map(_.value).toSet)
}

sealed trait DescriptionElements {
  def flatten: Seq[DescriptionElement]
}
final case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements {
  def flatten = elements.flatMap(_.flatten)
}
object SequenceOfElements {
  val empty = SequenceOfElements(Seq.empty)
}
sealed abstract class DescriptionElement(val kind: DescriptionElementKind) extends DescriptionElements {
  def flatten = Seq(this).filterNot(_.isNull)
  def text: String
  def url: Option[String]
  def isNull: Boolean = text.isEmpty // NB(ryan): named `isNull` because of scalac issues resolving str.isEmpty vs fromText(str).isEmpty when fromText is implicit
}
object DescriptionElement {
  implicit val format: Format[DescriptionElement] = Format(
    Reads { js =>
      (js \ "kind").validate[DescriptionElementKind].flatMap {
        case DescriptionElementKind.Text => TextElementHelper.format.reads(js)
        case DescriptionElementKind.Image => ImageElementHelper.format.reads(js)
        case DescriptionElementKind.ShowOriginal => ShowOriginalElementHelper.format.reads(js)
        case DescriptionElementKind.User => UserElementHelper.format.reads(js)
        case DescriptionElementKind.NonUser => NonUserElementHelper.format.reads(js)
        case DescriptionElementKind.Author => AuthorElementHelper.format.reads(js)
        case DescriptionElementKind.Library => LibraryElementHelper.format.reads(js)
        case DescriptionElementKind.Organization => OrganizationElementHelper.format.reads(js)
      }
    },
    Writes { de =>
      val specificFields = de match {
        case v: TextElement => TextElementHelper.format.writes(v)
        case v: ImageElement => ImageElementHelper.format.writes(v)
        case v: ShowOriginalElement => ShowOriginalElementHelper.format.writes(v)
        case v: UserElement => UserElementHelper.format.writes(v)
        case v: NonUserElement => NonUserElementHelper.format.writes(v)
        case v: AuthorElement => AuthorElementHelper.format.writes(v)
        case v: LibraryElement => LibraryElementHelper.format.writes(v)
        case v: OrganizationElement => OrganizationElementHelper.format.writes(v)
      }
      (Json.obj("kind" -> de.kind, "text" -> de.text, "url" -> de.url) ++ specificFields).nonNullFields
    }
  )
}

sealed abstract class DescriptionElementHelper[A <: DescriptionElement] {
  protected implicit val pathFormat: Format[Path] = Format(Path.format, Writes { o => JsString(o.absolute) })
  val format: OFormat[A]
}

case class TextElement(text: String, url: Option[String], hover: Option[DescriptionElements]) extends DescriptionElement(DescriptionElementKind.Text) {
  override def flatten = Seq(simplify)
  private def simplify = this.copy(url = url.filter(_.nonEmpty), hover = hover.filter(_.flatten.nonEmpty))

  def -->(link: LinkElement): TextElement = this.copy(url = Some(link.url))
  def -->(hover: Hover): TextElement = this.copy(hover = Some(hover.elements))
}
object TextElementHelper extends DescriptionElementHelper[TextElement] {
  private implicit val hoverFormat = DescriptionElements.format
  val format: OFormat[TextElement] = (
    (__ \ 'text).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'hover).formatNullable[DescriptionElements]
  )(TextElement.apply, unlift(TextElement.unapply))
}

final case class LinkElement(url: String)
object LinkElement {
  def apply(path: Path): LinkElement = LinkElement(path.absolute)
}
final case class Hover(elements: DescriptionElements*)

case class ImageElement(override val url: Option[String], image: String) extends DescriptionElement(DescriptionElementKind.Image) {
  override def isNull = image.isEmpty
  def text = ""
}
object ImageElementHelper extends DescriptionElementHelper[ImageElement] {
  val format: OFormat[ImageElement] = (
    (__ \ 'url).formatNullable[String] and
    (__ \ 'image).format[String]
  )(ImageElement.apply, unlift(ImageElement.unapply))
}

final case class ShowOriginalElement(original: String, updated: String) extends DescriptionElement(DescriptionElementKind.ShowOriginal) {
  val text = s"“$original” --> “$updated”"
  val url = None
}
object ShowOriginalElementHelper extends DescriptionElementHelper[ShowOriginalElement] {
  val format: OFormat[ShowOriginalElement] = (
    (__ \ 'original).format[String] and
    (__ \ 'updated).format[String]
  )(ShowOriginalElement.apply, unlift(ShowOriginalElement.unapply))
}

final case class UserElement(id: ExternalId[User], name: String, image: String, path: Path) extends DescriptionElement(DescriptionElementKind.User) {
  val text = name
  val url = Some(path.absolute)
}
object UserElementHelper extends DescriptionElementHelper[UserElement] {
  val format: OFormat[UserElement] = (
    (__ \ 'id).format[ExternalId[User]] and
    (__ \ 'text).format[String] and
    (__ \ 'image).format[String] and
    (__ \ 'url).format[Path]
  )(UserElement.apply, unlift(UserElement.unapply))
}

final case class NonUserElement(id: String) extends DescriptionElement(DescriptionElementKind.NonUser) {
  val text = id
  val url: Option[String] = None
}
object NonUserElementHelper extends DescriptionElementHelper[NonUserElement] {
  val format: OFormat[NonUserElement] = OFormat(
    Reads { js => (js \ "id").validate[String].map(NonUserElement) },
    OWrites[NonUserElement](o => Json.obj("id" -> o.id))
  )
}

final case class AuthorElement(
    id: String,
    name: String,
    image: String,
    override val url: Option[String],
    subtype: AuthorKind) extends DescriptionElement(DescriptionElementKind.Author) {
  val text = name
}
object AuthorElementHelper extends DescriptionElementHelper[AuthorElement] {
  val format: OFormat[AuthorElement] = (
    (__ \ 'id).format[String] and
    (__ \ 'text).format[String] and
    (__ \ 'image).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'subtype).format[AuthorKind]
  )(AuthorElement.apply, unlift(AuthorElement.unapply))
}

final case class LibraryElement(
    id: PublicId[Library],
    name: String,
    color: Option[LibraryColor],
    path: Path) extends DescriptionElement(DescriptionElementKind.Library) {
  val text = name
  val url = Some(path.absolute)
}
object LibraryElementHelper extends DescriptionElementHelper[LibraryElement] {
  val format: OFormat[LibraryElement] = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'text).format[String] and
    (__ \ 'color).formatNullable[LibraryColor] and
    (__ \ 'url).format[Path]
  )(LibraryElement.apply, unlift(LibraryElement.unapply))
}

final case class OrganizationElement(
    id: PublicId[Organization],
    name: String,
    image: String,
    path: Path) extends DescriptionElement(DescriptionElementKind.Organization) {
  val text = name
  val url = Some(path.absolute)
}
object OrganizationElementHelper extends DescriptionElementHelper[OrganizationElement] {
  val format: OFormat[OrganizationElement] = (
    (__ \ 'id).format[PublicId[Organization]] and
    (__ \ 'text).format[String] and
    (__ \ 'image).format[String] and
    (__ \ 'url).format[Path]
  )(OrganizationElement.apply _, unlift(OrganizationElement.unapply))
}

object DescriptionElements {
  def apply(elements: DescriptionElements*): SequenceOfElements = SequenceOfElements(elements)

  implicit def fromText(text: String): TextElement = TextElement(text, None, None)
  implicit def fromId[T](id: Id[T]): TextElement = fromText(id.id.toString)

  implicit def fromSeq[T](seq: Seq[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = SequenceOfElements(seq.map(toElements))
  implicit def fromOption[T](opt: Option[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = opt.toSeq

  implicit def fromInt(x: Int): TextElement = fromText(x.toString)
  implicit def fromLong(x: Long): TextElement = fromText(x.toString)
  implicit def fromEmailAddress(email: EmailAddress): TextElement = email.address
  implicit def fromDollarAmount(v: DollarAmount): TextElement = v.toDollarString
  implicit def fromRole(role: OrganizationRole): TextElement = role.value
  implicit def fromLocation(location: Location): TextElement = s"${location.context}: ${location.line}"
  implicit def fromSlackEmoji(emoji: SlackEmoji): TextElement = emoji.value
  implicit def fromSlackUsername(name: SlackUsername): TextElement = fromText("@" + name.value.stripPrefix("@"))

  implicit def fromDateTime(time: DateTime): TextElement = new PrettyTime().format(time.toDate)

  def generateUserElement(bu: BasicUser, fullName: Boolean)(implicit imageConfig: S3ImageConfig): UserElement = UserElement(bu.externalId, if (fullName) bu.fullName else bu.firstName, bu.picturePath.getUrl, Path(s"/${bu.username.value}"))
  implicit def fromBasicUser(bu: BasicUser)(implicit imageConfig: S3ImageConfig): UserElement = generateUserElement(bu, fullName = false)

  implicit def fromBasicOrg(bo: BasicOrganization): OrganizationElement = OrganizationElement(bo.orgId, bo.name, bo.avatarPath.path, bo.path)
  implicit def fromNonUser(bnu: BasicNonUser): NonUserElement = NonUserElement(bnu.id)
  implicit def fromBasicAuthor(ba: BasicAuthor): AuthorElement = ba match {
    case KifiUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url), ba.kind)
    case SlackUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url), ba.kind)
    case TwitterUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url), ba.kind)
    case EmailUser(id, name, picture) => AuthorElement(id, name, picture, None, ba.kind)
  }
  implicit def fromBasicLibrary(bl: BasicLibrary): LibraryElement = LibraryElement(bl.id, bl.name, bl.color, Path(bl.path))

  def inTheLast(x: Duration): TextElement = {
    val prettyTime = new PrettyTime(new java.util.Date(0))
    prettyTime.formatDuration(new java.util.Date(-x.getMillis)) match {
      case "" => "just now"
      case d => s"in the last ${d.stripPrefix("1 ")}"
    }
  }

  def intersperse[T](xs: Seq[T], ins: Seq[T]): Seq[T] = {
    (xs, ins) match {
      case (x, Seq()) => x
      case (Seq(), in) => in
      case (x +: xr, in +: inr) => x +: in +: intersperse(xr, inr)
    }
  }
  private def intersperseWith[T](xs: Seq[T], x: T): Seq[T] = {
    if (xs.isEmpty) xs
    else intersperse(xs, Seq.fill(xs.length - 1)(x))
  }

  def mkElements(els: Seq[DescriptionElements], e: DescriptionElement): DescriptionElements = SequenceOfElements(intersperseWith(els, e))
  def unlines(els: Seq[DescriptionElements]): DescriptionElements = mkElements(els, "\n")
  def unwordsPretty(els: Seq[DescriptionElements]): DescriptionElements = {
    els match {
      case Seq() => Seq()
      case Seq(x) => Seq(x)
      case Seq(x, y) => Seq[DescriptionElements](x, "and", y)
      case many => intersperse[DescriptionElements](many, Seq.fill(many.length - 2)(DescriptionElements(",")) :+ DescriptionElements(", and"))
    }
  }

  private def interpolatePunctuation(els: Seq[DescriptionElement]): Seq[DescriptionElement] = {
    if (els.isEmpty) Seq.empty
    else {
      val words = els.map(_.text)
      val wordPairs = words.init zip words.tail

      val leftEnds = Set("'", "\n", "[", "(", "`", " ", "“")
      val rightStarts = Set(".", ",", "'", "\n", "]", ")", "`", " ", "”", ":")
      val interpolatedPunctuation = wordPairs.map {
        case (l, r) if leftEnds.exists(l.endsWith) || rightStarts.exists(r.startsWith) => ""
        case _ => " "
      }.map(TextElement(_, None, None))
      intersperse(els, interpolatedPunctuation).filterNot(_.isNull)
    }
  }
  def formatPlain(description: DescriptionElements): String = interpolatePunctuation(description.flatten).map(_.text).mkString

  private def escapeSegment(segment: String): String = segment.replaceAllLiterally("<" -> "&lt;", ">" -> "&gt;", "&" -> "&amp;")
  def formatForSlack(description: DescriptionElements): String = {
    interpolatePunctuation(description.flatten).map { be =>
      be.url
        .map(u => s"<$u|${escapeSegment(be.text)}>")
        .getOrElse(escapeSegment(be.text))
    }.mkString
  }

  def formatAsHtml(description: DescriptionElements): Html = {
    val htmlStr = interpolatePunctuation(description.flatten).map { de =>
      val h = de match {
        case TextElement(_, _, hover) => hover.map(DescriptionElements.formatPlain).getOrElse("")
        case _ => ""
      }
      de.url
        .map(u => s"""<a href="$u" title="$h">${de.text}</a>""")
        .getOrElse(s"""<span title="$h">${de.text}</span>""")
    }.mkString
    Html(htmlStr)
  }

  private def combine(a: TextElement, b: TextElement): Option[TextElement] = {
    if (a.url == b.url && a.hover == b.hover) Some(TextElement(a.text + b.text, a.url, a.hover))
    else None
  }
  private def simplifyElements(els: Seq[DescriptionElement]): Seq[DescriptionElement] = els match {
    case Seq() => Seq()
    case Seq(x) => Seq(x)
    case x +: y +: rs => (x, y) match {
      case (x: TextElement, y: TextElement) =>
        combine(x, y).map(z => simplifyElements(z +: rs)).getOrElse(x +: simplifyElements(y +: rs))
      case _ => x +: simplifyElements(y +: rs)
    }
  }

  implicit val format: Format[DescriptionElements] = Format(
    Reads { js => js.validate[Seq[DescriptionElement]].map(SequenceOfElements(_)) },
    Writes { dsc =>
      JsArray(simplifyElements(interpolatePunctuation(dsc.flatten)).map(DescriptionElement.format.writes))
    }
  )
}
