package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.{ State }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time.Clock
import com.keepit.model.Name

import com.google.inject.{ ImplementedBy, Inject, Singleton }

@ImplementedBy(classOf[PaidPlanRepoImpl])
trait PaidPlanRepo extends Repo[PaidPlan] {
  def getByStates(states: Set[State[PaidPlan]])(implicit session: RSession): Seq[PaidPlan]
}

@Singleton
class PaidPlanRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[PaidPlan] with PaidPlanRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val dollarAmountColumnType = MappedColumnType.base[DollarAmount, Int](_.cents, DollarAmount(_))
  implicit val billingCycleColumnType = MappedColumnType.base[BillingCycle, Int](_.month, BillingCycle(_))

  type RepoImpl = PaidPlanTable
  class PaidPlanTable(tag: Tag) extends RepoTable[PaidPlan](db, tag, "paid_plan") {
    def name = column[Name[PaidPlan]]("name", O.NotNull)
    def billingCycle = column[BillingCycle]("billing_cycle", O.NotNull)
    def pricePerCyclePerUser = column[DollarAmount]("price_per_user_per_cycle", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, name, billingCycle, pricePerCyclePerUser) <> ((PaidPlan.apply _).tupled, PaidPlan.unapply _)
  }

  def table(tag: Tag) = new PaidPlanTable(tag)
  initTable()

  override def deleteCache(paidPlan: PaidPlan)(implicit session: RSession): Unit = {}

  override def invalidateCache(paidPlan: PaidPlan)(implicit session: RSession): Unit = {}

  def getByStates(states: Set[State[PaidPlan]])(implicit session: RSession): Seq[PaidPlan] = {
    (for (row <- rows if row.state.inSet(states)) yield row).list
  }

}
