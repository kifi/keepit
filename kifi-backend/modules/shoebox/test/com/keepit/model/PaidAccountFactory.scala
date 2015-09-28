package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments._
import com.keepit.common.time._

import org.joda.time.DateTime

object PaidAccountFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def paidAccount(): PartialPaidAccount = {
    new PartialPaidAccount(PaidAccount(id = Some(Id[PaidAccount](idx.incrementAndGet())), orgId = Id[Organization](idx.incrementAndGet()),
      planId = Id[PaidPlan](idx.incrementAndGet()), credit = DollarAmount(0), userContacts = Seq.empty, emailContacts = Seq.empty, activeUsers = 0, billingCycleStart = currentDateTime))
  }

  def paidAccounts(count: Int): Seq[PartialPaidAccount] = List.fill(count)(paidAccount())

  class PartialPaidAccount private[PaidAccountFactory] (account: PaidAccount) {
    def withId(id: Id[PaidAccount]) = new PartialPaidAccount(account.copy(id = Some(id)))
    def withOrganizationId(orgId: Id[Organization]) = new PartialPaidAccount(account.copy(orgId = orgId))
    def withOrganization(org: Organization) = new PartialPaidAccount(account.copy(orgId = org.id.get))
    def withPlan(planId: Id[PaidPlan]) = new PartialPaidAccount(account.copy(planId = planId))
    def withCredit(amount: DollarAmount) = new PartialPaidAccount(account.copy(credit = amount))
    def withBillingCycleStart(billingCycleStart: DateTime) = new PartialPaidAccount(account.copy(billingCycleStart = billingCycleStart))
    def withActiveUsers(activeUsers: Int) = new PartialPaidAccount(account.copy(activeUsers = activeUsers))
    def withFrozen(frozen: Boolean) = new PartialPaidAccount(account.copy(frozen = frozen))
    def get: PaidAccount = account
  }

  implicit class PartialPaidAccountSeq(accounts: Seq[PartialPaidAccount]) {
    def get: Seq[PaidAccount] = accounts.map(_.get)
  }
}
