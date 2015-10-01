package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments._
import org.apache.commons.lang3.RandomStringUtils.random

object PaidPlanFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  val testPlanEditableFeatures = Set.empty[Feature]
  val testPlanSettings: OrganizationSettings = OrganizationSettings(Set(
    FeatureSettingPair(Feature.PublishLibraries, Feature.PublishLibraries.MEMBERS),
    FeatureSettingPair(Feature.InviteMembers, Feature.InviteMembers.MEMBERS),
    FeatureSettingPair(Feature.MessageOrganization, Feature.MessageOrganization.MEMBERS),
    FeatureSettingPair(Feature.ForceEditLibraries, Feature.ForceEditLibraries.DISABLED),
    FeatureSettingPair(Feature.ViewOrganization, Feature.ViewOrganization.ANYONE),
    FeatureSettingPair(Feature.ViewMembers, Feature.ViewMembers.ANYONE),
    FeatureSettingPair(Feature.RemoveLibraries, Feature.RemoveLibraries.MEMBERS),
    FeatureSettingPair(Feature.CreateSlackIntegration, Feature.CreateSlackIntegration.DISABLED),
    FeatureSettingPair(Feature.EditOrganization, Feature.EditOrganization.ADMINS),
    FeatureSettingPair(Feature.ExportKeeps, Feature.ExportKeeps.ADMINS)
  ))

  def paidPlan(): PartialPaidPlan = {
    new PartialPaidPlan(PaidPlan(id = Some(Id[PaidPlan](idx.incrementAndGet())), kind = PaidPlan.Kind.NORMAL, name = Name(random(5)),
      pricePerCyclePerUser = DollarAmount(10000), billingCycle = BillingCycle(month = 1), editableFeatures = testPlanEditableFeatures, defaultSettings = testPlanSettings))
  }

  class PartialPaidPlan private[PaidPlanFactory] (plan: PaidPlan) {
    def withPricePerCyclePerUser(pricePerCyclePerUser: DollarAmount) = new PartialPaidPlan(plan.copy(pricePerCyclePerUser = pricePerCyclePerUser))
    def withBillingCycle(billingCycle: BillingCycle) = new PartialPaidPlan(plan.copy(billingCycle = billingCycle))
    def get: PaidPlan = plan
  }

  implicit class PartialPaidPlanSeq(plans: Seq[PartialPaidPlan]) {
    def get: Seq[PaidPlan] = plans.map(_.get)
  }
}
