package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time.Clock
import com.keepit.common.db.{ Id, State }

import com.google.inject.{ ImplementedBy, Inject, Singleton }

@ImplementedBy(classOf[PaymentMethodRepoImpl])
trait PaymentMethodRepo extends Repo[PaymentMethod] {
  def getByAccountId(accountId: Id[PaidAccount], excludeStates: Set[State[PaymentMethod]] = Set(PaymentMethodStates.INACTIVE))(implicit session: RSession): Seq[PaymentMethod]
}

@Singleton
class PaymentMethodRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[PaymentMethod] with PaymentMethodRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val stripeTokenColumnType = MappedColumnType.base[StripeToken, String](_.token, StripeToken(_))

  //Put this in a common package? Still needs specific tests
  //ZZZ NOT CORRECT YET!!
  implicit val trueOrNullColumnType = MappedColumnType.base[TrueOrNull, Boolean](
    ton => ton.v,
    ob => TrueOrNull(ob)
  )

  type RepoImpl = PaymentMethodTable
  class PaymentMethodTable(tag: Tag) extends RepoTable[PaymentMethod](db, tag, "paid_plan") {
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def default = column[TrueOrNull]("default", O.Nullable)
    def stripeToken = column[StripeToken]("stripe_token", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, accountId, default, stripeToken) <> ((PaymentMethod.apply _).tupled, PaymentMethod.unapply _)
  }

  def table(tag: Tag) = new PaymentMethodTable(tag)
  initTable()

  override def deleteCache(paymentMethod: PaymentMethod)(implicit session: RSession): Unit = {}

  override def invalidateCache(paymentMethod: PaymentMethod)(implicit session: RSession): Unit = {}

  def getByAccountId(accountId: Id[PaidAccount], excludeStates: Set[State[PaymentMethod]] = Set(PaymentMethodStates.INACTIVE))(implicit session: RSession): Seq[PaymentMethod] = {
    (for (row <- rows if row.accountId === accountId && !row.state.inSet(excludeStates)) yield row).list
  }

}
