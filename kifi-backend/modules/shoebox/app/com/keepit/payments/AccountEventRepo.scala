package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.time._
import com.keepit.model.{ SortDirection, Offset, Limit, User }

import com.google.inject.{ ImplementedBy, Inject, Singleton }

import play.api.libs.json.JsValue

import org.joda.time.DateTime

@ImplementedBy(classOf[AccountEventRepoImpl])
trait AccountEventRepo extends Repo[AccountEvent] {
  def getByAccount(accountId: Id[PaidAccount], offset: Offset, limit: Limit, excludeStateOpt: Option[State[AccountEvent]] = Some(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent]
  def getAllByAccount(accountId: Id[PaidAccount], excludeStateOpt: Option[State[AccountEvent]] = Some(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent]

  def getByAccountAndKinds(accountId: Id[PaidAccount], kinds: Set[AccountEventKind], fromIdOpt: Option[Id[AccountEvent]], limit: Limit, excludeStateOpt: Option[State[AccountEvent]] = Some(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent]

  def getMembershipEventsInOrder(accountId: Id[PaidAccount])(implicit session: RSession): Seq[AccountEvent]
  def adminGetRecentEvents(limit: Limit)(implicit session: RSession): Seq[AccountEvent]

  def deactivateAll(accountId: Id[PaidAccount])(implicit session: RWSession): Int
}

@Singleton
class AccountEventRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[AccountEvent] with AccountEventRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  case class AccountEventKindNotFoundException(s: String) extends Exception(s"""AccountEventKind "$s" not found""")
  implicit val dollarAmountColumnType = DollarAmount.columnType(db)
  implicit val accountEventKindMapper = MappedColumnType.base[AccountEventKind, String](_.value, s => AccountEventKind.get(s).getOrElse(throw new AccountEventKindNotFoundException(s))) // explicitly requires "good" data

  type RepoImpl = AccountEventTable

  class AccountEventTable(tag: Tag) extends RepoTable[AccountEvent](db, tag, "account_event") {
    def eventTime = column[DateTime]("event_time", O.NotNull)
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def whoDunnit = column[Option[Id[User]]]("whodunnit", O.Nullable)
    def whoDunnitExtra = column[Option[JsValue]]("whodunnit_extra", O.Nullable)
    def kifiAdminInvolved = column[Option[Id[User]]]("kifi_admin_involved", O.Nullable)
    def eventType = column[AccountEventKind]("event_type", O.NotNull)
    def eventTypeExtras = column[Option[JsValue]]("event_type_extras", O.Nullable)
    def creditChange = column[DollarAmount]("credit_change", O.NotNull)
    def paymentMethod = column[Option[Id[PaymentMethod]]]("payment_method", O.Nullable)
    def paymentCharge = column[Option[DollarAmount]]("payment_charge", O.Nullable)
    def memo = column[Option[String]]("memo", O.Nullable)
    def chargeId = column[Option[String]]("charge_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, eventTime, accountId, whoDunnit, whoDunnitExtra, kifiAdminInvolved, eventType, eventTypeExtras, creditChange, paymentMethod, paymentCharge, memo, chargeId) <> ((AccountEvent.applyFromDbRow _).tupled, AccountEvent.unapplyFromDbRow _)
  }

  def age(ae: AccountEventTable) = (ae.eventTime desc, ae.id desc)

  def table(tag: Tag) = new AccountEventTable(tag)
  initTable()
  override def deleteCache(accountEvent: AccountEvent)(implicit session: RSession): Unit = {}
  override def invalidateCache(accountEvent: AccountEvent)(implicit session: RSession): Unit = {}

  private def getByAccountHelper(accountId: Id[PaidAccount], excludeStateOpt: Option[State[AccountEvent]])(implicit session: RSession) =
    rows.filter(ae => ae.accountId === accountId && ae.state =!= excludeStateOpt.orNull)
  private def getByAccountAndKindsHelper(accountId: Id[PaidAccount], kinds: Set[AccountEventKind], excludeStateOpt: Option[State[AccountEvent]])(implicit session: RSession) =
    getByAccountHelper(accountId, excludeStateOpt).filter(ae => ae.eventType.inSet(kinds))

  def getByAccount(accountId: Id[PaidAccount], offset: Offset, limit: Limit, excludeStateOpt: Option[State[AccountEvent]] = Some(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent] = {
    getByAccountHelper(accountId, excludeStateOpt)
      .sortBy(age)
      .drop(offset.value)
      .take(limit.value)
      .list
  }
  def getAllByAccount(accountId: Id[PaidAccount], excludeStateOpt: Option[State[AccountEvent]] = Some(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent] = {
    getByAccountHelper(accountId, excludeStateOpt)
      .sortBy(age)
      .list
  }

  def getByAccountAndKinds(accountId: Id[PaidAccount], kinds: Set[AccountEventKind], fromIdOpt: Option[Id[AccountEvent]], limit: Limit, excludeStateOpt: Option[State[AccountEvent]] = Some(AccountEventStates.INACTIVE))(implicit session: RSession): Seq[AccountEvent] = {
    val allEvents = getByAccountAndKindsHelper(accountId, kinds, excludeStateOpt)
    val filteredEvents = fromIdOpt match {
      case None => allEvents
      case Some(fromId) =>
        val fromTime = rows.filter(_.id === fromId).map(_.eventTime).first
        allEvents.filter(ae => ae.eventTime < fromTime || (ae.eventTime === fromTime && ae.id < fromId))
    }
    filteredEvents
      .sortBy(age)
      .take(limit.value)
      .list
  }

  def getMembershipEventsInOrder(accountId: Id[PaidAccount])(implicit session: RSession): Seq[AccountEvent] = {
    val (userAdded, userRemoved): (AccountEventKind, AccountEventKind) = (AccountEventKind.UserAdded, AccountEventKind.UserRemoved)
    (for (row <- rows if row.accountId === accountId && (row.eventType === userAdded || row.eventType === userRemoved)) yield row).sortBy(r => (r.eventTime asc, r.id asc)).list
  }
  def adminGetRecentEvents(limit: Limit)(implicit session: RSession): Seq[AccountEvent] = {
    rows.filter(row => row.state === AccountEventStates.ACTIVE).sortBy(age).take(limit.value).list
  }
  def deactivateAll(accountId: Id[PaidAccount])(implicit session: RWSession): Int = {
    (for (row <- rows if row.accountId === accountId) yield (row.state, row.updatedAt)).update((AccountEventStates.INACTIVE, clock.now))
  }
}
