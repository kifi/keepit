package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.logging.Logging
import com.keepit.common.util.DollarAmount
import com.keepit.payments.CreditRewardFail.UnrepeatableRewardKeyCollisionException
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[CreditRewardRepoImpl])
trait CreditRewardRepo extends Repo[CreditReward] {
  def create(model: CreditReward)(implicit session: RWSession): Try[CreditReward]
  def getByReward(reward: Reward)(implicit session: RSession): Set[CreditReward]
  def getByCreditCode(code: CreditCode)(implicit session: RSession): Set[CreditReward]
  def getByAccount(accountId: Id[PaidAccount])(implicit session: RSession): Set[CreditReward]

  def deactivate(model: CreditReward)(implicit session: RWSession): Unit
  def deactivateAll(accountId: Id[PaidAccount])(implicit session: RSession): Int
}

// Unique index on (code, singleUse) - single-use codes can only be used once overall
// Unique index on (kind, unrepeatable) - two codes of the same unrepeatable kind cannot be applied
// Referential integrity constraint from CreditReward.code CreditCode.code
// Referential integrity constraint from CreditReward.accountId to PaidAccount.id
// Referential integrity constraint from CreditReward.{accountId, applied, credit} to AccountEvent.{accountId, id, creditChange}
@Singleton
class CreditRewardRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CreditReward] with CreditRewardRepo with Logging {

  import db.Driver.simple._

  implicit val kindColumnType = MappedColumnType.base[RewardKind, String](_.kind, RewardKind.apply)
  implicit val creditCodeTypeMapper = CreditCode.columnType(db)
  implicit val unrepeatableTypeMapper = MappedColumnType.base[UnrepeatableRewardKey, String](_.toKey, UnrepeatableRewardKey.fromKey)

  type RepoImpl = CreditRewardTable
  class CreditRewardTable(tag: Tag) extends RepoTable[CreditReward](db, tag, "credit_reward") {
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)
    def applied = column[Option[Id[AccountEvent]]]("applied", O.Nullable)
    def kind = column[RewardKind]("kind", O.NotNull)
    def status = column[String]("status", O.NotNull)
    def info = column[Option[JsValue]]("info", O.Nullable)
    def unrepeatable = column[Option[UnrepeatableRewardKey]]("unrepeatable", O.Nullable)

    def code = column[Option[CreditCode]]("code", O.Nullable)
    def singleUse = column[Option[Boolean]]("single_use", O.Nullable)
    def usedBy = column[Option[Id[User]]]("used_by", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, accountId, credit, applied, kind, status, info, unrepeatable, code, singleUse, usedBy) <> ((CreditRewardRepo.applyFromDbRow _).tupled, CreditRewardRepo.unapplyToDbRow)
  }

  def table(tag: Tag) = new CreditRewardTable(tag)
  initTable()

  override def deleteCache(creditReward: CreditReward)(implicit session: RSession): Unit = {}
  override def invalidateCache(creditReward: CreditReward)(implicit session: RSession): Unit = {}

  private def activeRows = rows.filter(_.state === CreditRewardStates.ACTIVE)

  def create(model: CreditReward)(implicit session: RWSession): Try[CreditReward] = {
    if (model.unrepeatable.exists(key => rows.filter(row => row.unrepeatable === key).exists.run)) Failure(UnrepeatableRewardKeyCollisionException(model.unrepeatable.get))
    else Success(save(model))
  }

  def getByReward(reward: Reward)(implicit session: RSession): Set[CreditReward] = {
    val kind = reward.kind
    val status = reward.kind.writeStatus(reward.status)
    val info = reward.status.infoFormat.writes(reward.info)
    activeRows.filter(row => row.kind === kind && row.status === status && row.info === info).list.toSet
  }

  def getByCreditCode(code: CreditCode)(implicit session: RSession): Set[CreditReward] = {
    activeRows.filter(row => row.code === code).list.toSet
  }

  def getByAccount(accountId: Id[PaidAccount])(implicit session: RSession): Set[CreditReward] = {
    activeRows.filter(row => row.accountId === accountId).list.toSet
  }

  def deactivate(model: CreditReward)(implicit session: RWSession): Unit = save(model.withState(CreditRewardStates.INACTIVE))

  def deactivateAll(accountId: Id[PaidAccount])(implicit session: RSession): Int = {
    (for (r <- rows if r.accountId === accountId) yield (r.state, r.updatedAt, r.singleUse)).update((CreditRewardStates.INACTIVE, clock.now(), None))
  }
}

object CreditRewardRepo {
  def applyFromDbRow(
    id: Option[Id[CreditReward]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[CreditReward],
    accountId: Id[PaidAccount],
    credit: DollarAmount,
    applied: Option[Id[AccountEvent]],
    kind: RewardKind,
    status: String,
    info: Option[JsValue],
    unrepeatable: Option[UnrepeatableRewardKey],
    code: Option[CreditCode],
    singleUse: Option[Boolean],
    usedBy: Option[Id[User]]): CreditReward = {
    CreditReward(
      id,
      createdAt,
      updatedAt,
      state,
      accountId,
      credit,
      applied,
      Reward.applyFromDbRow(kind, status, info),
      unrepeatable,
      code.map(UsedCreditCode.applyFromDbRow(_, singleUse, usedBy.get))
    )
  }

  def unapplyToDbRow(creditReward: CreditReward) = {
    Reward.unapplyToDbRow(creditReward.reward).map {
      case (kind, status, info) =>
        val (code, singleUse, usedBy) = creditReward.code.flatMap(UsedCreditCode.unapplyToDbRow).map {
          case (actualCode, su, userId) => (Some(actualCode), su, Some(userId))
        }.getOrElse(None, None, None)
        (
          creditReward.id,
          creditReward.createdAt,
          creditReward.updatedAt,
          creditReward.state,
          creditReward.accountId,
          creditReward.credit,
          creditReward.applied,
          kind,
          status,
          info,
          creditReward.unrepeatable,
          code,
          singleUse,
          usedBy
        )
    }
  }
}
