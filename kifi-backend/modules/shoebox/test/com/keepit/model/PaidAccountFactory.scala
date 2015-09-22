package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments._
import com.keepit.common.time._

object PaidAccountFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  val defaultSettings = PaidPlanFactory.testPlanFeatures.map { feature => FeatureSetting(feature.name, feature.default) }

  def paidAccount(): PartialPaidAccount = {
    new PartialPaidAccount(PaidAccount(id = Some(Id[PaidAccount](idx.incrementAndGet())), orgId = Id[Organization](idx.incrementAndGet()),
      planId = Id[PaidPlan](idx.incrementAndGet()), credit = DollarAmount(0), userContacts = Seq.empty, emailContacts = Seq.empty, featureSettings = defaultSettings, activeUsers = 0, billingCycleStart = currentDateTime))
  }

  def paidAccounts(count: Int): Seq[PartialPaidAccount] = List.fill(count)(paidAccount())

  class PartialPaidAccount private[PaidAccountFactory] (account: PaidAccount) {
    def withId(id: Id[PaidAccount]) = new PartialPaidAccount(account.copy(id = Some(id)))
    def withOrganization(orgId: Id[Organization]) = new PartialPaidAccount(account.copy(orgId = orgId))
    def withPlan(planId: Id[PaidPlan]) = new PartialPaidAccount(account.copy(planId = planId))
    def withCredit(amount: DollarAmount) = new PartialPaidAccount(account.copy(credit = amount))
    def withSetting(featureSetting: FeatureSetting) = new PartialPaidAccount(account.copy(featureSettings = FeatureSetting.alterSettings(account.featureSettings, Set(featureSetting))))
    def withSettings(featureSettings: Set[FeatureSetting]) = new PartialPaidAccount(account.copy(featureSettings = FeatureSetting.alterSettings(account.featureSettings, featureSettings)))
    def get: PaidAccount = account
  }

  implicit class PartialPaidAccountSeq(accounts: Seq[PartialPaidAccount]) {
    def get: Seq[PaidAccount] = accounts.map(_.get)
  }
}
