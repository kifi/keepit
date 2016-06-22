package com.keepit.shoebox.data.keep

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.json.{ JsonSchema, SchemaReads }
import com.keepit.common.mail.{ EmailAddressHash, EmailAddress }
import com.keepit.common.reflection.Enumerator
import com.keepit.common.util.PaginationContext
import com.keepit.model.{ KeepRecipients, User, Library, BasicLibrary, Keep }
import com.keepit.social.BasicUser
import play.api.libs.json.{ JsString, Json, Writes, Reads }

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

sealed trait ExternalKeepRecipientId
object ExternalKeepRecipientId {
  case class UserId(id: ExternalId[User]) extends ExternalKeepRecipientId
  case class LibraryId(id: PublicId[Library]) extends ExternalKeepRecipientId
  case class Email(address: EmailAddress) extends ExternalKeepRecipientId

  def fromStr(s: String): Option[ExternalKeepRecipientId] = {
    ExternalId.asOpt[User](s).map(UserId)
      .orElse(Library.validatePublicId(s).toOption.map(LibraryId))
      .orElse(EmailAddress.validate(s).toOption.map(Email))
  }

  implicit def sreads: SchemaReads[ExternalKeepRecipientId] = {
    val reads: Reads[ExternalKeepRecipientId] = Seq(
      Reads(ExternalId.format[User].reads).map[ExternalKeepRecipientId](UserId),
      Reads(Library.readsPublicId.reads).map[ExternalKeepRecipientId](LibraryId),
      Reads(EmailAddress.format.reads).map[ExternalKeepRecipientId](Email)
    ).reduce(_ orElse _)
    SchemaReads(reads, schema = JsonSchema.Single("either an external user id, public library id, or email address"))
  }
}

sealed trait KeepRecipientId {
  def toKeepRecipients: KeepRecipients
}
object KeepRecipientId {
  case class UserId(id: Id[User]) extends KeepRecipientId {
    def toKeepRecipients: KeepRecipients = KeepRecipients.EMPTY.plusUser(id)
  }
  case class LibraryId(id: Id[Library]) extends KeepRecipientId {
    def toKeepRecipients: KeepRecipients = KeepRecipients.EMPTY.plusLibrary(id)
  }
  case class Email(address: EmailAddress) extends KeepRecipientId {
    lazy val addressHash: EmailAddressHash = EmailAddressHash.hashEmailAddress(address)
    def toKeepRecipients: KeepRecipients = KeepRecipients.EMPTY.plusEmailAddress(address)
  }
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
