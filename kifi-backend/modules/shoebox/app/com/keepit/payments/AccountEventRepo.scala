package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.time.Clock
import com.keepit.model.User

import com.google.inject.{ ImplementedBy, Inject, Singleton }

import play.api.libs.json.JsValue

import org.joda.time.DateTime

@ImplementedBy(classOf[AccountEventRepoImpl])
trait AccountEventRepo extends Repo[AccountEvent] {
  def getByGroupId(group: EventGroup, excludeStates: Set[State[AccountEvent]] = Set(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent]

  def getByAccountIdAndTime(accountId: Id[PaidAccount], before: DateTime, max: Int = 10, excludeStates: Set[State[AccountEvent]] = Set(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent]

  def getByAccountAndState(accountId: Id[PaidAccount], state: State[AccountEvent])(implicit session: RSession): Seq[AccountEvent]

}

@Singleton
class AccountEventRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[AccountEvent] with AccountEventRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val dollarAmountColumnType = MappedColumnType.base[DollarAmount, Int](_.cents, DollarAmount(_))
  implicit val eventGroupColumnType = MappedColumnType.base[EventGroup, Long](_.id, EventGroup(_))

  type RepoImpl = AccountEventTable
  class AccountEventTable(tag: Tag) extends RepoTable[AccountEvent](db, tag, "paid_plan") {
    def eventGroup = column[EventGroup]("event_group", O.NotNull)
    def eventTime = column[DateTime]("event_time", O.NotNull)
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def billingRelated = column[Boolean]("billing_related", O.NotNull)
    def whoDunnit = column[Id[User]]("whodunnit", O.Nullable)
    def whoDunnitExtra = column[JsValue]("whodunnit_extra", O.NotNull)
    def kifiAdminInvolved = column[Id[User]]("kifi_admin_involved", O.Nullable)
    def eventType = column[String]("event_type", O.NotNull)
    def eventTypeExtras = column[JsValue]("event_type_extras", O.NotNull)
    def creditChange = column[DollarAmount]("credit_change", O.NotNull)
    def paymentMethod = column[Id[PaymentMethod]]("payment_method", O.Nullable)
    def paymentCharge = column[DollarAmount]("payment_charge", O.Nullable)
    def memo = column[String]("memo", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, eventGroup, eventTime, accountId, billingRelated, whoDunnit.?, whoDunnitExtra, kifiAdminInvolved.?, eventType, eventTypeExtras, creditChange, paymentMethod.?, paymentCharge.?, memo.?) <> ((AccountEvent.applyFromDbRow _).tupled, AccountEvent.unapplyFromDbRow _)
  }

  def table(tag: Tag) = new AccountEventTable(tag)
  initTable()

  override def deleteCache(accountEvent: AccountEvent)(implicit session: RSession): Unit = {}

  override def invalidateCache(accountEvent: AccountEvent)(implicit session: RSession): Unit = {}

  def getByGroupId(group: EventGroup, excludeStates: Set[State[AccountEvent]] = Set(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent] = {
    (for (row <- rows if row.eventGroup === group && !row.state.inSet(excludeStates)) yield row).list
  }

  def getByAccountIdAndTime(accountId: Id[PaidAccount], before: DateTime, max: Int = 10, excludeStates: Set[State[AccountEvent]] = Set(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent] = {
    (for (row <- rows if row.accountId === accountId && row.eventTime < before && !row.state.inSet(excludeStates)) yield row).sortBy(row => row.eventTime desc).take(max).list
  }

  def getByAccountAndState(accountId: Id[PaidAccount], state: State[AccountEvent])(implicit session: RSession): Seq[AccountEvent] = {
    (for (row <- rows if row.accountId === accountId && row.state === state) yield row).list
  }

}
