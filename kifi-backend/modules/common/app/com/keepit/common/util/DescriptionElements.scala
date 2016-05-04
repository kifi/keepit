package com.keepit.common.util

import com.keepit.common.core.jsObjectExtensionOps
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
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
import play.twirl.api.Html

sealed trait DescriptionElements {
  def flatten: Seq[DescriptionElement]
}
final case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements {
  def flatten = elements.flatMap(_.flatten)
}
object SequenceOfElements {
  val empty = SequenceOfElements(Seq.empty)
}
sealed abstract class DescriptionElement(val kind: String) extends DescriptionElements {
  def flatten = Seq(this).filterNot(_.isNull)
  val text: String
  val url: Option[String]
  protected def isNull: Boolean = text.isEmpty // NB(ryan): named `isNull` because of scalac issues resolving str.isEmpty vs fromText(str).isEmpty when fromText is implicit
  def asJson: JsObject
}
object DescriptionElement {
  implicit val writes: OWrites[DescriptionElement] = OWrites { de => de.asJson.nonNullFields + ("kind" -> JsString(de.kind)) }
}

final case class TextElement(text: String, url: Option[String], hover: Option[DescriptionElements]) extends DescriptionElement("text") {
  override def flatten = Seq(simplify)
  private def simplify = this.copy(url = url.filter(_.nonEmpty), hover = hover.filter(_.flatten.nonEmpty))

  def -->(link: LinkElement): TextElement = this.copy(url = Some(link.url))
  def -->(hover: Hover): TextElement = this.copy(hover = Some(hover.elements))

  def asJson = Json.obj("text" -> text, "url" -> url, "hover" -> hover)
}

final case class LinkElement(url: String)
object LinkElement {
  def apply(path: Path): LinkElement = LinkElement(path.absolute)
}
final case class Hover(elements: DescriptionElements*)

final case class ImageElement(override val url: Option[String], image: String) extends DescriptionElement("image") {
  override protected def isNull = image.isEmpty

  val text = ""
  def asJson = Json.obj("text" -> text, "image" -> image, "url" -> url)
}

final case class ShowOriginalElement(original: String, updated: String) extends DescriptionElement("showOriginal") {
  val text = s"“$original” --> “$updated”"
  val url = None

  def asJson = Json.obj("text" -> text, "original" -> original, "updated" -> updated)
}

final case class UserElement(
    id: ExternalId[User],
    name: String,
    image: String,
    path: Path) extends DescriptionElement("user") {
  val text = name
  val url = Some(path.absolute)
  def asJson = Json.obj("id" -> id, "text" -> text, "image" -> image, "url" -> url)
}

final case class NonUserElement(id: String) extends DescriptionElement("nonUser") {
  val text = id
  val url = None
  def asJson = Json.obj("text" -> text)
}

final case class AuthorElement(
    id: String,
    name: String,
    image: String,
    override val url: Option[String],
    subtype: AuthorKind) extends DescriptionElement("author") {
  val text = name
  def asJson = Json.obj("id" -> id, "text" -> text, "image" -> image, "url" -> url, "subtype" -> subtype.value)
}

final case class LibraryElement(
    id: PublicId[Library],
    name: String,
    color: Option[LibraryColor],
    path: Path) extends DescriptionElement("library") {
  val text = name
  val url = Some(path.absolute)
  def asJson = Json.obj("id" -> id, "text" -> text, "color" -> color, "url" -> url)
}

final case class OrganizationElement(
    id: PublicId[Organization],
    name: String,
    image: String,
    path: Path) extends DescriptionElement("organization") {
  val text = name
  val url = Some(path.absolute)
  def asJson = Json.obj("id" -> id, "text" -> text, "image" -> image, "url" -> url)
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
      intersperse(els, interpolatedPunctuation).filterNot(_.text.isEmpty)
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
  implicit val flatWrites: Writes[DescriptionElements] = Writes { dsc =>
    JsArray(simplifyElements(interpolatePunctuation(dsc.flatten)).map(DescriptionElement.writes.writes))
  }
}
