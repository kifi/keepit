package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.common.util.DollarAmount
import com.keepit.payments._

object PaidPlanFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  // tests will fail when adding new Features, update 398.sql with new features and their default settings
  val testPlanEditableFeatures = Feature.all -- Set(StaticFeature.ViewOrganization, StaticFeature.ViewSettings)
  val testPlanSettings: OrganizationSettings = OrganizationSettings.empty.setAll(Map(
    StaticFeature.PublishLibraries -> StaticFeatureSetting.MEMBERS,
    StaticFeature.InviteMembers -> StaticFeatureSetting.MEMBERS,
    StaticFeature.GroupMessaging -> StaticFeatureSetting.MEMBERS,
    StaticFeature.ForceEditLibraries -> StaticFeatureSetting.DISABLED,
    StaticFeature.ViewOrganization -> StaticFeatureSetting.ANYONE,
    StaticFeature.ViewMembers -> StaticFeatureSetting.ANYONE,
    StaticFeature.RemoveLibraries -> StaticFeatureSetting.MEMBERS,
    StaticFeature.CreateSlackIntegration -> StaticFeatureSetting.MEMBERS,
    StaticFeature.EditOrganization -> StaticFeatureSetting.ADMINS,
    StaticFeature.ExportKeeps -> StaticFeatureSetting.ADMINS,
    StaticFeature.ViewSettings -> StaticFeatureSetting.MEMBERS,
    StaticFeature.JoinByVerifying -> StaticFeatureSetting.NONMEMBERS,
    StaticFeature.SlackIngestionReaction -> StaticFeatureSetting.DISABLED,
    StaticFeature.SlackNotifications -> StaticFeatureSetting.ENABLED
  ))

  def paidPlan(): PartialPaidPlan = {
    new PartialPaidPlan(PaidPlan(id = Some(Id[PaidPlan](idx.incrementAndGet())), kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("test"), displayName = "Free",
      pricePerCyclePerUser = DollarAmount.cents(10000), billingCycle = BillingCycle(months = 1), editableFeatures = testPlanEditableFeatures, defaultSettings = testPlanSettings))
  }

  class PartialPaidPlan private[PaidPlanFactory] (plan: PaidPlan) {
    def withPricePerCyclePerUser(pricePerCyclePerUser: DollarAmount) = new PartialPaidPlan(plan.copy(pricePerCyclePerUser = pricePerCyclePerUser))
    def withPricePerCyclePerUser(cents: Int) = new PartialPaidPlan(plan.copy(pricePerCyclePerUser = DollarAmount.cents(cents)))
    def withBillingCycle(billingCycle: BillingCycle) = new PartialPaidPlan(plan.copy(billingCycle = billingCycle))
    def withKind(kind: PaidPlan.Kind) = new PartialPaidPlan(plan.copy(kind = kind))
    def withDisplayName(name: String) = new PartialPaidPlan(plan.copy(displayName = name))
    def withDefaultSettings(defaultSettings: OrganizationSettings) = new PartialPaidPlan(plan.copy(defaultSettings = defaultSettings))
    def withEditableFeatures(editableFeatures: Set[Feature]) = new PartialPaidPlan(plan.copy(editableFeatures = editableFeatures))
    def get: PaidPlan = plan
  }

  implicit class PartialPaidPlanSeq(plans: Seq[PartialPaidPlan]) {
    def get: Seq[PaidPlan] = plans.map(_.get)
  }
}
