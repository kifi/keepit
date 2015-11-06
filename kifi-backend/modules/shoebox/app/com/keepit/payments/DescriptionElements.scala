package com.keepit.payments

import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
import com.keepit.model.{ OrganizationRole, OrganizationHandle, BasicOrganization }
import com.keepit.social.BasicUser
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.twirl.api.Html

sealed trait DescriptionElements {
  def flatten: Seq[BasicElement]
}
case class SequenceOfElements(elements: Seq[DescriptionElements]) extends DescriptionElements {
  def flatten = elements.flatMap(_.flatten)
}
case class BasicElement(text: String, url: Option[String], hover: Option[DescriptionElements]) extends DescriptionElements {
  def flatten = Seq(this)
  def withText(newText: String) = this.copy(text = newText)
  def withUrl(newUrl: String) = this.copy(url = Some(newUrl))
  def withHover(newHover: DescriptionElements) = this.copy(hover = Some(newHover))

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
case class Hover(elements: DescriptionElements)
object Hover {
  def apply(elements: DescriptionElements*): Hover = Hover(SequenceOfElements(elements))
}
object DescriptionElements {
  def apply(elements: DescriptionElements*): SequenceOfElements = SequenceOfElements(elements)

  implicit def fromText(text: String): BasicElement = BasicElement(text, None, None)

  implicit def fromSeq[T](seq: Seq[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = SequenceOfElements(seq.map(toElements))
  implicit def fromOption[T](opt: Option[T])(implicit toElements: T => DescriptionElements): SequenceOfElements = opt.toSeq

  implicit def fromInt(x: Int): BasicElement = x.toString
  implicit def fromCreditCode(code: CreditCode): BasicElement = code.value
  implicit def fromBasicUser(user: BasicUser): BasicElement = user.firstName --> LinkElement(user.path.absolute)
  implicit def fromBasicOrg(org: BasicOrganization): BasicElement = org.name --> LinkElement(org.path.absolute)
  implicit def fromEmailAddress(email: EmailAddress): BasicElement = email.address
  implicit def fromDollarAmount(v: DollarAmount): BasicElement = v.toDollarString
  implicit def fromPaidPlanAndUrl(plan: PaidPlan)(implicit orgHandle: OrganizationHandle): BasicElement = plan.fullName --> LinkElement(Path(s"${orgHandle.value}/settings/plan").absolute)
  implicit def fromRole(role: OrganizationRole): BasicElement = role.value

  private def intersperse[T](xs: List[T], ins: List[T]): List[T] = {
    (xs, ins) match {
      case (x :: Nil, Nil) => x :: Nil
      case (x :: xr, in :: inr) => x :: in :: intersperse(xr, inr)
      case _ => throw new IllegalArgumentException(s"intersperse expects lists with length (n, n-1). it got (${xs.length}, ${ins.length})")
    }
  }
  private def interpolatePunctuation(els: Seq[BasicElement]): Seq[BasicElement] = {
    val words = els.map(_.text).toList
    val wordPairs = words.init zip words.tail
    val interpolatedPunctuation = wordPairs.map {
      case (l, r) if l.endsWith("'") || r.startsWith(".") || r.startsWith("'") => ""
      case _ => " "
    }.map(BasicElement(_, None, None))
    intersperse(els.toList, interpolatedPunctuation).filter(_.text.nonEmpty)
  }

  def formatPlain(description: DescriptionElements): String = interpolatePunctuation(description.flatten).map(_.text).mkString
  def formatForSlack(description: DescriptionElements): String = {
    interpolatePunctuation(description.flatten).map { be =>
      be.url
        .map(u => s"<$u|${be.text}>")
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

  implicit val flatWrites: Writes[DescriptionElements] = Writes { dsc =>
    JsArray(interpolatePunctuation(dsc.flatten).map(BasicElement.writes.writes))
  }
}
