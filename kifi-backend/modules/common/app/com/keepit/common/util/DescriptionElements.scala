package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.strings.StringWithReplacements
import com.keepit.common.time._
import com.keepit.macros.Location
import com.keepit.model.{ BasicLibrary, Organization, LibraryColor, Library, User, BasicOrganization, OrganizationRole }
import com.keepit.slack.models.{ SlackUsername, SlackEmoji }
import com.keepit.social.BasicAuthor.{EmailUser, TwitterUser, SlackUser, KifiUser}
import com.keepit.social.{ BasicAuthor, BasicNonUser, BasicUser }
import org.joda.time.{ Duration, DateTime, Period }
import org.ocpsoft.prettytime.PrettyTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.twirl.api.Html

sealed trait DescriptionElements {
  def flatten: Seq[DescriptionElement]
}
case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements {
  def flatten = elements.flatMap(_.flatten).filter(_.text.nonEmpty)
}
object SequenceOfElements {
  val empty = SequenceOfElements(Seq.empty)
}
sealed trait DescriptionElement extends DescriptionElements { self =>
  type A >: self.type <: DescriptionElement
  val text: String
  val url: Option[String]

  def helper: DescriptionElementHelper[A]
}
object DescriptionElement {
  implicit val writes = Writes[DescriptionElement](o => o.helper.writes.writes(o))
}
abstract class DescriptionElementHelper[A](val kind: String) {
  implicit val writes: Writes[A]
}
case class TextElement(text: String, url: Option[String], hover: Option[DescriptionElements]) extends DescriptionElement {
  type A = TextElement
  def flatten = Seq(simplify)
  def withText(newText: String) = this.copy(text = newText)
  def withUrl(newUrl: String) = this.copy(url = Some(newUrl))
  def withHover(newHover: DescriptionElements) = this.copy(hover = Some(newHover))

  def simplify = this.copy(url = url.filter(_.nonEmpty), hover = hover.filter(_.flatten.nonEmpty))
  def combineWith(that: TextElement): Option[TextElement] = {
    if (this.url == that.url && this.hover == that.hover) Some(TextElement(this.text + that.text, url, hover))
    else None
  }

  def -->(link: LinkElement): TextElement = this.withUrl(link.url)
  def -->(hover: Hover): TextElement = this.withHover(hover.elements)

  def helper = TextElement
}
object TextElement extends DescriptionElementHelper[TextElement]("text") {
  implicit val writes: Writes[TextElement] = Writes(t => Json.obj("text" -> t.text, "url" -> t.url, "hover" -> t.hover, "kind" -> kind))
}

case class LinkElement(url: String)
object LinkElement {
  def apply(path: Path): LinkElement = LinkElement(path.absolute)
}
case class Hover(elements: DescriptionElements*)

case class ImageElement(override val url: Option[String], image: String) extends DescriptionElement {
  type A = ImageElement
  def flatten = if (image.isEmpty) Seq.empty else Seq(this)
  def helper = ImageElement

  val text = ""
}
object ImageElement extends DescriptionElementHelper[ImageElement]("image") {
  implicit val writes: Writes[ImageElement] = Writes(i => Json.obj("text" -> i.text, "image" -> i.image, "kind" -> kind))
}

case class UserElement(
    id: ExternalId[User],
    name: String,
    image: String,
    path: Path) extends DescriptionElement {
  type A = UserElement
  def flatten = Seq(this)
  def helper = UserElement

  val text = name
  val url = Some(path.absolute)
}
object UserElement extends DescriptionElementHelper[UserElement]("user") {
  implicit val writes: Writes[UserElement] = Writes { u => Json.obj("id" -> u.id, "text" -> u.text, "image" -> u.image, "url" -> u.url, "kind" -> kind) }
}

case class NonUserElement(id: String) extends DescriptionElement {
  type A = NonUserElement
  def flatten = if (id.isEmpty) Seq.empty else Seq(this)
  def helper = NonUserElement

  val text = id
  val url = None
}
object NonUserElement extends DescriptionElementHelper[NonUserElement]("nonUser") {
  implicit val writes: Writes[NonUserElement] = Writes { n => Json.obj("text" -> n.text, "kind" -> kind) }
}

case class AuthorElement(
    id: String,
    name: String,
    image: String,
    override val url: Option[String]) extends DescriptionElement {
  type A = AuthorElement
  def flatten = Seq(this)
  def helper = AuthorElement

  val text = name
}
object AuthorElement extends DescriptionElementHelper[AuthorElement]("author") {
  implicit val writes: Writes[AuthorElement] = Writes{ a => Json.obj("id" -> a.id, "text" -> a.text, "image" -> a.image, "url" -> a.url, "kind" -> kind) }
}

case class LibraryElement(
    id: PublicId[Library],
    name: String,
    color: Option[LibraryColor],
    path: Path) extends DescriptionElement {
  type A = LibraryElement
  def flatten = Seq(this)
  def helper = LibraryElement

  val text = name
  val url = Some(path.absolute)
}
object LibraryElement extends DescriptionElementHelper[LibraryElement]("library") {
  implicit val writes: Writes[LibraryElement] = Writes { l => Json.obj("id" -> l.id, "text" -> l.text, "color" -> l.color, "url" -> l.url, "kind" -> kind) }
}

case class OrganizationElement(
    id: PublicId[Organization],
    name: String,
    image: String,
    path: Path) extends DescriptionElement {
  type A = OrganizationElement
  def flatten = Seq(this)
  def helper = OrganizationElement

  val text = name
  val url = Some(path.absolute)
}
object OrganizationElement extends DescriptionElementHelper[OrganizationElement]("organization") {
  implicit val writes: Writes[OrganizationElement] = Writes { o => Json.obj("id" -> o.id, "text" -> o.text, "image" -> o.image, "url" -> o.url, "kind" -> kind) }
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

  implicit def fromBasicUser(bu: BasicUser)(implicit imageConfig: S3ImageConfig): UserElement = UserElement(bu.externalId, bu.firstName, bu.picturePath.getUrl, Path(s"/${bu.username.value}"))
  implicit def fromBasicOrg(bo: BasicOrganization): OrganizationElement = OrganizationElement(bo.orgId, bo.name, bo.avatarPath.path, bo.path)
  implicit def fromNonUser(bnu: BasicNonUser): NonUserElement = NonUserElement(bnu.id)
  implicit def fromBasicAuthor(ba: BasicAuthor): AuthorElement = ba match {
    case KifiUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url))
    case SlackUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url))
    case TwitterUser(id, name, picture, url) => AuthorElement(id, name, picture, Some(url))
    case EmailUser(id, name, picture) => AuthorElement(id, name, picture, None)
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
      intersperse(els, interpolatedPunctuation).filter(_.text.nonEmpty)
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

  private def simplifyElements(els: Seq[DescriptionElement]): Seq[DescriptionElement] = els match {
    case Seq() => Seq()
    case Seq(x) => Seq(x)
    case x +: y +: rs => (x, y) match {
      case (x: TextElement, y: TextElement) =>
        (x combineWith y).map(z => simplifyElements(z +: rs)).getOrElse(x +: simplifyElements(y +: rs))
      case (x, y) => x +: simplifyElements(y +: rs)
    }
  }
  implicit val flatWrites: Writes[DescriptionElements] = Writes { dsc =>
    JsArray(simplifyElements(interpolatePunctuation(dsc.flatten)).map(DescriptionElement.writes.writes))
  }
}
