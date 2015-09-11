package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments.{ BillingCycle, DollarAmount, PaidPlan }
import org.apache.commons.lang3.RandomStringUtils.random

object PaidPlanFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def paidPlan(): PartialPaidPlan = {
    new PartialPaidPlan(PaidPlan(id = Some(Id[PaidPlan](idx.incrementAndGet())), kind = PaidPlan.Kind.NORMAL, name = Name(random(5)),
      pricePerCyclePerUser = DollarAmount(10000), billingCycle = BillingCycle(month = 1), features = Set.empty))
  }

  def paidPlans(count: Int): Seq[PartialPaidPlan] = List.fill(count)(paidPlan())

  class PartialPaidPlan private[PaidPlanFactory] (plan: PaidPlan) {
    def withId(id: Id[PaidPlan]) = new PartialPaidPlan(plan.copy(id = Some(id)))
    def withId(id: Int) = new PartialPaidPlan(plan.copy(id = Some(Id[PaidPlan](id))))
    def withKind(kind: PaidPlan.Kind) = new PartialPaidPlan(plan.copy(kind = kind))
    def withName(name: Name[PaidPlan]) = new PartialPaidPlan(plan.copy(name = name))
    def withPricePerCyclePerUser(pricePerCyclePerUser: DollarAmount) = new PartialPaidPlan(plan.copy(pricePerCyclePerUser = pricePerCyclePerUser))
    def withBillingCycle(billingCycle: BillingCycle) = new PartialPaidPlan(plan.copy(billingCycle = billingCycle))
    def get: PaidPlan = plan
  }

  implicit class PartialPaidPlanSeq(plans: Seq[PartialPaidPlan]) {
    def get: Seq[PaidPlan] = plans.map(_.get)
  }
}
