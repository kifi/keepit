package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments.{ DollarAmount, PaidAccount, PaidPlan }

object PaidAccountFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def paidAccount(): PartialPaidAccount = {
    new PartialPaidAccount(PaidAccount(id = Some(Id[PaidAccount](idx.incrementAndGet())), orgId = Id[Organization](idx.incrementAndGet()),
      planId = Id[PaidPlan](idx.incrementAndGet()), credit = DollarAmount(0), userContacts = Seq.empty, emailContacts = Seq.empty))
  }

  def paidAccounts(count: Int): Seq[PartialPaidAccount] = List.fill(count)(paidAccount())

  class PartialPaidAccount private[PaidAccountFactory] (plan: PaidAccount) {
    def withId(id: Id[PaidAccount]) = new PartialPaidAccount(plan.copy(id = Some(id)))
    def withOrganization(orgId: Id[Organization]) = new PartialPaidAccount(plan.copy(orgId = orgId))
    def withPlan(planId: Id[PaidPlan]) = new PartialPaidAccount(plan.copy(planId = planId))
    def withCredit(amount: DollarAmount) = new PartialPaidAccount(plan.copy())
    def get: PaidAccount = plan
  }

  implicit class PartialPaidAccountSeq(accounts: Seq[PartialPaidAccount]) {
    def get: Seq[PaidAccount] = accounts.map(_.get)
  }
}
