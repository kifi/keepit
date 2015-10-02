package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments._
import org.apache.commons.lang3.RandomStringUtils.random

object PaidPlanFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  val testPlanEditableFeatures = Feature.all
  val testPlanSettings: OrganizationSettings = OrganizationSettings.empty.setAll(Map(
    Feature.PublishLibraries -> FeatureSetting.MEMBERS,
    Feature.InviteMembers -> FeatureSetting.MEMBERS,
    Feature.GroupMessaging -> FeatureSetting.MEMBERS,
    Feature.ForceEditLibraries -> FeatureSetting.DISABLED,
    Feature.ViewOrganization -> FeatureSetting.ANYONE,
    Feature.ViewMembers -> FeatureSetting.ANYONE,
    Feature.RemoveLibraries -> FeatureSetting.MEMBERS,
    Feature.CreateSlackIntegration -> FeatureSetting.DISABLED,
    Feature.EditOrganization -> FeatureSetting.ADMINS,
    Feature.ExportKeeps -> FeatureSetting.ADMINS
  ))

  def paidPlan(): PartialPaidPlan = {
    new PartialPaidPlan(PaidPlan(id = Some(Id[PaidPlan](idx.incrementAndGet())), kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("test"),
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
