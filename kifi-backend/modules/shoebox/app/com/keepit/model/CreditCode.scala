package com.keepit.model

import java.net.URLEncoder
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.payments.DollarAmount
import org.joda.time.DateTime
import play.api.libs.json._

import scala.concurrent.duration.Duration
import scala.util.Try

case class CreditCode(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object CreditCode {
  def normalize(rawCode: String): CreditCode = CreditCode(rawCode.toLowerCase.trim)
  implicit val format: Format[CreditCode] = Format(Reads.of[String].map(normalize(_)), Writes[CreditCode](code => JsString(code.value)))
  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[CreditCode, String](_.value, CreditCode.apply)
  }
}

sealed abstract class CreditCodeKind(val kind: String)
object CreditCodeKind {
  case object Coupon extends CreditCodeKind("coupon")
  case object OrgReferral extends CreditCodeKind("org_referral")

  def isSingleUse(kind: CreditCodeKind) = kind match {
    case Coupon => true
    case OrgReferral => false
  }

  private val all: Set[CreditCodeKind] = Set(Coupon, OrgReferral)

  def apply(kind: String) = all.find(_.kind equalsIgnoreCase kind) match {
    case Some(validKind) => validKind
    case None => throw new IllegalArgumentException(s"Unknown CreditCodeKind: $kind")
  }
}

case class CreditCodeReferrer(userId: Id[User], organizationId: Option[Id[Organization]], credit: Option[DollarAmount])

sealed abstract class CreditCodeStatus(val value: String)
object CreditCodeStatus {
  case object Open extends CreditCodeStatus("open")
  case object Closed extends CreditCodeStatus("closed")

  private val all = Set(Open, Closed)
  def apply(value: String) = all.find(_.value equalsIgnoreCase value) match {
    case Some(validStatus) => validStatus
    case None => throw new IllegalArgumentException(s"Invalid CreditCodeStatus: $value")
  }
}

object CreditCodeInfoStates extends States[CreditCodeInfo]

// Unique index on (code)
case class CreditCodeInfo(
    id: Option[Id[CreditCodeInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[CreditCodeInfo] = CreditCodeInfoStates.ACTIVE,
    code: CreditCode,
    kind: CreditCodeKind,
    credit: DollarAmount,
    status: CreditCodeStatus,
    validityPeriod: Option[Duration],
    referrer: Option[CreditCodeReferrer]) extends ModelWithState[CreditCodeInfo] {
  def withId(id: Id[CreditCodeInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object CreditCodeInfo {
  def applyFromDbRow(
    id: Option[Id[CreditCodeInfo]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[CreditCodeInfo],
    code: CreditCode,
    kind: CreditCodeKind,
    credit: DollarAmount,
    status: CreditCodeStatus,
    validityPeriod: Option[Duration],
    referrerUserId: Option[Id[User]],
    referrerOrganizationId: Option[Id[Organization]],
    referrerCredit: Option[DollarAmount]) = {
    val referrer = referrerUserId.map(CreditCodeReferrer(_, referrerOrganizationId, referrerCredit))
    CreditCodeInfo(id, createdAt, updatedAt, state, code, kind, credit, status, validityPeriod, referrer)
  }

  def unapplyToDbRow(info: CreditCodeInfo) = Some((
    info.id,
    info.createdAt,
    info.updatedAt,
    info.state,
    info.code,
    info.kind,
    info.credit,
    info.status,
    info.validityPeriod,
    info.referrer.map(_.userId),
    info.referrer.flatMap(_.organizationId),
    info.referrer.flatMap(_.credit)
  ))
}

case class UnavailableCreditCodeException(info: CreditCodeInfo) extends Exception(s"Credit code ${info.code} is unavailable, already exists with kind ${info.kind.kind}")

@ImplementedBy(classOf[CreditCodeInfoRepoImpl])
trait CreditCodeInfoRepo extends Repo[CreditCodeInfo] {
  def getByCode(code: CreditCode, excludeState: Option[State[CreditCodeInfo]] = Some(CreditCodeInfoStates.INACTIVE))(implicit session: RSession): Option[CreditCodeInfo]
  def insert(info: CreditCodeInfo)(implicit session: RWSession): Try[CreditCodeInfo]
}

@Singleton
class CreditCodeInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CreditCodeInfo] with CreditCodeInfoRepo with Logging {

  import db.Driver.simple._

  implicit val creditCodeTypeMapper = CreditCode.columnType(db)
  implicit val dollarAmountColumnType = DollarAmount.columnType(db)
  implicit val kindColumnType = MappedColumnType.base[CreditCodeKind, String](_.kind, CreditCodeKind.apply)
  implicit val statusColumnType = MappedColumnType.base[CreditCodeStatus, String](_.value, CreditCodeStatus.apply)

  type RepoImpl = CreditCodeInfoTable
  class CreditCodeInfoTable(tag: Tag) extends RepoTable[CreditCodeInfo](db, tag, "credit_code_info") {
    def code = column[CreditCode]("code", O.NotNull)
    def kind = column[CreditCodeKind]("kind", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)
    def status = column[CreditCodeStatus]("status", O.NotNull)
    def validityPeriod = column[Option[Duration]]("validity_period", O.Nullable)
    def referrerUserId = column[Option[Id[User]]]("referrer_user_id", O.Nullable)
    def referrerOrganizationId = column[Option[Id[Organization]]]("referrer_organization_id", O.Nullable)
    def referrerCredit = column[Option[DollarAmount]]("referrer_credit", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, code, kind, credit, status, validityPeriod, referrerUserId, referrerOrganizationId, referrerCredit) <> ((CreditCodeInfo.applyFromDbRow _).tupled, CreditCodeInfo.unapplyToDbRow)
  }

  def table(tag: Tag) = new CreditCodeInfoTable(tag)
  initTable()

  override def deleteCache(info: CreditCodeInfo)(implicit session: RSession): Unit = {}

  override def invalidateCache(info: CreditCodeInfo)(implicit session: RSession): Unit = {}

  def getByCode(code: CreditCode, excludeState: Option[State[CreditCodeInfo]] = Some(CreditCodeInfoStates.INACTIVE))(implicit session: RSession): Option[CreditCodeInfo] = ???
  def insert(info: CreditCodeInfo)(implicit session: RWSession): Try[CreditCodeInfo] = ???
}
