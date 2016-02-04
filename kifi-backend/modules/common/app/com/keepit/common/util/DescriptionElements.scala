package com.keepit.common.util

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
import com.keepit.common.strings.StringWithReplacements
import com.keepit.common.time._
import com.keepit.macros.Location
import com.keepit.model.{ BasicOrganization, OrganizationRole }
import com.keepit.slack.models.SlackEmoji
import com.keepit.social.BasicUser
import org.joda.time.{ Duration, DateTime, Period }
import org.ocpsoft.prettytime.PrettyTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.twirl.api.Html

sealed trait DescriptionElements {
  def flatten: Seq[BasicElement]
}
case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements {
  def flatten = elements.flatMap(_.flatten).filter(_.text.nonEmpty)
}
object SequenceOfElements {
  val empty = SequenceOfElements(Seq.empty)
}
case class BasicElement(text: String, url: Option[String], hover: Option[DescriptionElements]) extends DescriptionElements {
  def flatten = Seq(simplify)
  def withText(newText: String) = this.copy(text = newText)
  def withUrl(newUrl: String) = this.copy(url = Some(newUrl))
  def withHover(newHover: DescriptionElements) = this.copy(hover = Some(newHover))

  def simplify = this.copy(url = url.filter(_.nonEmpty), hover = hover.filter(_.flatten.nonEmpty))
  def combineWith(that: BasicElement): Option[BasicElement] = {
    if (this.url == that.url && this.hover == that.hover) Some(BasicElement(this.text + that.text, url, hover))
    else None
  }

  def -->(link: LinkElement): BasicElement = this.withUrl(link.url)
  def -->(hover: Hover): BasicElement = this.withHover(hover.elements)
}
object BasicElement {
  implicit val writes: Writes[BasicElement] = (
    (__ \ 'text).write[String] and
    (__ \ 'url).writeNullable[String] and
    (__ \ 'hover).writeNullable[DescriptionElements]
  )(unlift(BasicElement.unapply))
}

case class LinkElement(url: String)
object LinkElement {
  def apply(path: Path): LinkElement = LinkElement(path.absolute)
}
case class Hover(elements: DescriptionElements*)
object DescriptionElements {
  def apply(elements: DescriptionElements*): SequenceOfElements = SequenceOfElements(elements)

  implicit def fromText(text: String): BasicElement = BasicElement(text, None, None)
  implicit def fromId[T](id: Id[T]): BasicElement = fromText(id.id.toString)

  implicit def fromSeq[T](seq: Seq[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = SequenceOfElements(seq.map(toElements))
  implicit def fromOption[T](opt: Option[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = opt.toSeq

  implicit def fromInt(x: Int): BasicElement = x.toString
  implicit def fromBasicUser(user: BasicUser): BasicElement = user.firstName --> LinkElement(user.path.absolute)
  implicit def fromBasicOrg(org: BasicOrganization): BasicElement = org.name --> LinkElement(org.path.absolute)
  implicit def fromEmailAddress(email: EmailAddress): BasicElement = email.address
  implicit def fromDollarAmount(v: DollarAmount): BasicElement = v.toDollarString
  implicit def fromRole(role: OrganizationRole): BasicElement = role.value
  implicit def fromLocation(location: Location): BasicElement = s"${location.context}: ${location.line}"
  implicit def fromSlackEmoji(emoji: SlackEmoji): BasicElement = emoji.value

  implicit def fromDateTime(time: DateTime): BasicElement = new PrettyTime().format(time.toDate)
  def inTheLast(x: Duration): BasicElement = {
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

  def mkElements(els: Seq[DescriptionElements], e: BasicElement): DescriptionElements = SequenceOfElements(intersperseWith(els, e))
  def unlines(els: Seq[DescriptionElements]): DescriptionElements = mkElements(els, "\n")
  def unwordsPretty(els: Seq[DescriptionElements]): DescriptionElements = els match {
    case Seq() => Seq()
    case Seq(x) => Seq(x)
    case Seq(x, y) => Seq[DescriptionElements](x, "and", y)
    case many => intersperse[DescriptionElements](many, Seq.fill(many.length - 2)(DescriptionElements(",")) :+ DescriptionElements(", and"))
  }

  private def interpolatePunctuation(els: Seq[BasicElement]): Seq[BasicElement] = {
    val words = els.map(_.text)
    val wordPairs = words.init zip words.tail

    val leftEnds = Set("'", "\n", "[", "(", "`", " ", "“")
    val rightStarts = Set(".", ",", "'", "\n", "]", ")", "`", " ", "”")
    val interpolatedPunctuation = wordPairs.map {
      case (l, r) if leftEnds.exists(l.endsWith) || rightStarts.exists(r.startsWith) => ""
      case _ => " "
    }.map(BasicElement(_, None, None))
    intersperse(els, interpolatedPunctuation).filter(_.text.nonEmpty)
  }

  def formatPlain(description: DescriptionElements): String = interpolatePunctuation(description.flatten).map(_.text).mkString

  private def escapeSegment(segment: String): String = segment.replaceAllLiterally("<" -> "&lt;", ">" -> "&gt;", "&" -> "&amp;")
  def formatForSlack(description: DescriptionElements): String = {
    interpolatePunctuation(description.flatten).map { be =>
      be.url
        .map(u => s"<$u|${escapeSegment(be.text)}>")
        .getOrElse(be.text)
    }.mkString
  }

  def formatAsHtml(description: DescriptionElements): Html = {
    val htmlStr = interpolatePunctuation(description.flatten).map { be =>
      val h = be.hover.map(DescriptionElements.formatPlain).getOrElse("")
      be.url
        .map(u => s"""<a href="$u" title="$h">${be.text}</a>""")
        .getOrElse(s"""<span title="$h">${be.text}</span>""")
    }.mkString
    Html(htmlStr)
  }

  private def simplifyElements(els: Seq[BasicElement]): Seq[BasicElement] = els match {
    case Seq() => Seq()
    case Seq(x) => Seq(x)
    case x +: y +: rs => x combineWith y match {
      case Some(z) => simplifyElements(z +: rs)
      case None => x +: simplifyElements(y +: rs)
    }
  }
  implicit val flatWrites: Writes[DescriptionElements] = Writes { dsc =>
    JsArray(simplifyElements(interpolatePunctuation(dsc.flatten)).map(BasicElement.writes.writes))
  }
}
