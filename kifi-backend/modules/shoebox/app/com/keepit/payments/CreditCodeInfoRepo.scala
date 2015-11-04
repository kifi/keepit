package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.{ Organization, User }
import com.keepit.payments.CreditRewardFail.UnavailableCreditCodeException
import org.joda.time.DateTime

import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[CreditCodeInfoRepoImpl])
trait CreditCodeInfoRepo extends Repo[CreditCodeInfo] {
  def getByOrg(orgId: Id[Organization], excludeStateOpt: Option[State[CreditCodeInfo]] = Some(CreditCodeInfoStates.INACTIVE))(implicit session: RSession): Option[CreditCodeInfo]
  def getByCode(code: CreditCode, excludeStatus: Option[CreditCodeStatus] = Some(CreditCodeStatus.Closed), excludeStateOpt: Option[State[CreditCodeInfo]] = Some(CreditCodeInfoStates.INACTIVE))(implicit session: RSession): Option[CreditCodeInfo]
  def create(info: CreditCodeInfo)(implicit session: RWSession): Try[CreditCodeInfo]

  def close(info: CreditCodeInfo)(implicit session: RWSession): Unit
}

@Singleton
class CreditCodeInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CreditCodeInfo] with CreditCodeInfoRepo with Logging {

  import db.Driver.simple._

  implicit val creditCodeTypeMapper = CreditCode.columnType(db)
  implicit val dollarAmountColumnType = DollarAmount.columnType(db)
  implicit val kindColumnType = CreditCodeKind.columnType(db)
  implicit val statusColumnType = CreditCodeStatus.columnType(db)

  type RepoImpl = CreditCodeInfoTable
  class CreditCodeInfoTable(tag: Tag) extends RepoTable[CreditCodeInfo](db, tag, "credit_code_info") {
    def code = column[CreditCode]("code", O.NotNull)
    def kind = column[CreditCodeKind]("kind", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)
    def status = column[CreditCodeStatus]("status", O.NotNull)
    def referrerUserId = column[Option[Id[User]]]("referrer_user_id", O.Nullable)
    def referrerOrganizationId = column[Option[Id[Organization]]]("referrer_organization_id", O.Nullable)
    def referrerCredit = column[Option[DollarAmount]]("referrer_credit", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, code, kind, credit, status, referrerUserId, referrerOrganizationId, referrerCredit) <> ((CreditCodeInfoRepo.applyFromDbRow _).tupled, CreditCodeInfoRepo.unapplyToDbRow)
  }

  def table(tag: Tag) = new CreditCodeInfoTable(tag)
  initTable()

  override def deleteCache(info: CreditCodeInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: CreditCodeInfo)(implicit session: RSession): Unit = {}

  def getByOrg(orgId: Id[Organization], excludeStateOpt: Option[State[CreditCodeInfo]] = Some(CreditCodeInfoStates.INACTIVE))(implicit session: RSession): Option[CreditCodeInfo] = {
    rows.filter(row => row.referrerOrganizationId === orgId && row.state =!= excludeStateOpt.orNull).firstOption
  }
  def getByCode(code: CreditCode, excludeStatus: Option[CreditCodeStatus] = Some(CreditCodeStatus.Closed), excludeStateOpt: Option[State[CreditCodeInfo]] = Some(CreditCodeInfoStates.INACTIVE))(implicit session: RSession): Option[CreditCodeInfo] = {
    rows.filter(row => row.code === code && row.status =!= excludeStatus.orNull && row.state =!= excludeStateOpt.orNull).firstOption
  }
  def create(info: CreditCodeInfo)(implicit session: RWSession): Try[CreditCodeInfo] = {
    if (rows.filter(row => row.code === info.code).exists.run) Failure(UnavailableCreditCodeException(info.code))
    else Success(save(info))
  }
  def close(info: CreditCodeInfo)(implicit session: RWSession): Unit = {
    save(info.withStatus(CreditCodeStatus.Closed))
  }
}

object CreditCodeInfoRepo {
  def applyFromDbRow(
    id: Option[Id[CreditCodeInfo]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[CreditCodeInfo],
    code: CreditCode,
    kind: CreditCodeKind,
    credit: DollarAmount,
    status: CreditCodeStatus,
    referrerUserId: Option[Id[User]],
    referrerOrganizationId: Option[Id[Organization]],
    referrerCredit: Option[DollarAmount]) = {
    val referrer = (referrerUserId, referrerCredit) match {
      case (Some(refUserId), Some(refCredit)) => Some(CreditCodeReferrer(refUserId, referrerOrganizationId, refCredit))
      case (None, None) => None
      case _ => throw new Exception(s"DB row for credit code info $id is in a super broken state")
    }
    CreditCodeInfo(id, createdAt, updatedAt, state, code, kind, credit, status, referrer)
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
    info.referrer.map(_.userId),
    info.referrer.flatMap(_.organizationId),
    info.referrer.map(_.credit)
  ))
}
