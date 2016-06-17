package com.keepit.shoebox.data.keep

import com.keepit.common.reflection.Enumerator
import com.keepit.common.util.PaginationContext
import com.keepit.model.Keep
import play.api.libs.json.{ JsString, Json, Writes }
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
  page: Option[NewPageInfo],
  paginationContext: PaginationContext[Keep],
  intersectionKeeps: Seq[NewKeepInfo],
  onThisPageKeeps: Seq[(NewKeepInfo, KeepProximitySection)],
  entityName: Option[String] // name of either user or library we're filtering on
  )

object NewKeepInfosForIntersection {
  val empty = NewKeepInfosForIntersection(page = Option.empty, paginationContext = PaginationContext.empty, intersectionKeeps = Seq.empty, onThisPageKeeps = Seq.empty, entityName = None)
  implicit val writes: Writes[NewKeepInfosForIntersection] = {
    implicit val helper: Writes[(NewKeepInfo, KeepProximitySection)] = Writes {
      case (info, section) => NewKeepInfo.writes.writes(info) + ("section" -> KeepProximitySection.writes.writes(section))
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
