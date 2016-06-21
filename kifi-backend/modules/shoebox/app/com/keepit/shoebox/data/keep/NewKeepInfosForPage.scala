package com.keepit.shoebox.data.keep

import com.keepit.common.mail.EmailAddress
import com.keepit.common.reflection.Enumerator
import com.keepit.common.util.PaginationContext
import com.keepit.model.{ BasicLibrary, Keep }
import com.keepit.social.BasicUser
import play.api.libs.json.{ JsNull, JsString, Json, Writes }
import play.api.libs.functional.syntax._

case class NewKeepInfosForPage(
  page: Option[NewPageInfo],
  paginationContext: PaginationContext[Keep],
  keeps: Seq[(NewKeepInfo, KeepProximitySection)])

object NewKeepInfosForPage {
  val empty = NewKeepInfosForPage(page = Option.empty, paginationContext = PaginationContext.empty, keeps = Seq.empty)
  implicit val writes: Writes[NewKeepInfosForPage] = {
    implicit val helper: Writes[(NewKeepInfo, KeepProximitySection)] = Writes {
      case (info, section) => NewKeepInfo.writes.writes(info) + ("section" -> KeepProximitySection.writes.writes(section))
    }
    Json.writes[NewKeepInfosForPage]
  }
}

case class NewKeepInfosForIntersection(
  paginationContext: PaginationContext[Keep],
  keeps: Seq[NewKeepInfo],
  intersector: Option[ExternalKeepRecipient]) // name of either user or library we're filtering on

sealed trait ExternalKeepRecipient
object ExternalKeepRecipient {
  case class UserRecipient(bu: BasicUser) extends ExternalKeepRecipient
  case class LibraryRecipient(bl: BasicLibrary) extends ExternalKeepRecipient
  case class EmailRecipient(ea: EmailAddress) extends ExternalKeepRecipient
}

object NewKeepInfosForIntersection {
  val empty = NewKeepInfosForIntersection(paginationContext = PaginationContext.empty, keeps = Seq.empty, intersector = None)
  implicit val writes: Writes[NewKeepInfosForIntersection] = {
    import ExternalKeepRecipient._
    implicit val tupleWrites: Writes[ExternalKeepRecipient] = Writes {
      case UserRecipient(bu) => Json.toJson(bu)
      case LibraryRecipient(bl) => Json.toJson(bl)
      case EmailRecipient(ea) => Json.toJson(ea)
    }
    Json.writes[NewKeepInfosForIntersection]
  }
}

sealed abstract class KeepProximitySection(val priority: Int, val value: String)
object KeepProximitySection extends Enumerator[KeepProximitySection] {
  case object Direct extends KeepProximitySection(0, "direct")
  case object LibraryMembership extends KeepProximitySection(1, "library")
  case object OrganizationLibrary extends KeepProximitySection(2, "organization")
  case object PublishedLibrary extends KeepProximitySection(3, "public")
  val all = _all
  def fromInt(x: Int): KeepProximitySection = all.find(_.priority == x).getOrElse {
    throw new IllegalArgumentException(s"Bad priority for KeepProximitySelection: $x")
  }
  implicit val ord: Ordering[KeepProximitySection] = Ordering.by(_.priority)
  implicit val writes: Writes[KeepProximitySection] = Writes { p => JsString(p.value) }
}
