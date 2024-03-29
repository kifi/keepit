package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time.Clock
import com.keepit.common.db.{ Id, State }

import com.google.inject.{ ImplementedBy, Inject, Singleton }

@ImplementedBy(classOf[PaymentMethodRepoImpl])
trait PaymentMethodRepo extends Repo[PaymentMethod] {
  def getByAccountId(accountId: Id[PaidAccount], excludeStates: Set[State[PaymentMethod]] = Set(PaymentMethodStates.INACTIVE))(implicit session: RSession): Seq[PaymentMethod]
  def getDefault(accountId: Id[PaidAccount])(implicit session: RSession): Option[PaymentMethod]
  def deactivate(model: PaymentMethod)(implicit session: RWSession): Unit
}

@Singleton
class PaymentMethodRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[PaymentMethod] with PaymentMethodRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val stripeTokenColumnType = MappedColumnType.base[StripeToken, String](_.token, StripeToken(_))

  type RepoImpl = PaymentMethodTable
  class PaymentMethodTable(tag: Tag) extends RepoTable[PaymentMethod](db, tag, "payment_method") {
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def default = column[Option[Boolean]]("default_method", O.Nullable)
    def stripeToken = column[StripeToken]("stripe_token", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, accountId, default, stripeToken) <> ((PaymentMethod.applyFromDbRow _).tupled, PaymentMethod.unapplyFromDbRow _)
  }

  def table(tag: Tag) = new PaymentMethodTable(tag)
  initTable()

  override def deleteCache(paymentMethod: PaymentMethod)(implicit session: RSession): Unit = {}

  override def invalidateCache(paymentMethod: PaymentMethod)(implicit session: RSession): Unit = {}

  def getByAccountId(accountId: Id[PaidAccount], excludeStates: Set[State[PaymentMethod]] = Set(PaymentMethodStates.INACTIVE))(implicit session: RSession): Seq[PaymentMethod] = {
    (for (row <- rows if row.accountId === accountId && !row.state.inSet(excludeStates)) yield row).list
  }

  def getDefault(accountId: Id[PaidAccount])(implicit session: RSession): Option[PaymentMethod] = {
    (for (row <- rows if row.accountId === accountId && row.default === true) yield row).firstOption
  }

  def deactivate(model: PaymentMethod)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

}
