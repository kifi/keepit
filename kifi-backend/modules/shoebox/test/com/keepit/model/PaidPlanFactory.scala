package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments._
import org.apache.commons.lang3.RandomStringUtils.random

object PaidPlanFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  val testPlanEditableFeatures = Set.empty[Feature]
  val testPlanSettings: OrganizationSettings = OrganizationSettings(
    publishLibraries = Feature.PublishLibrariesSetting.MEMBERS,
    inviteMembers = Feature.InviteMembersSetting.MEMBERS,
    messageOrganization = Feature.MessageOrganizationSetting.MEMBERS,
    forceEditLibraries = Feature.ForceEditLibrariesSetting.DISABLED,
    viewMembers = Feature.ViewMembersSetting.ANYONE,
    removeLibraries = Feature.RemoveLibrariesSetting.MEMBERS,
    createSlackIntegration = Feature.CreateSlackIntegrationSetting.DISABLED,
    editOrganization = Feature.EditOrganizationSetting.ADMINS,
    exportKeeps = Feature.ExportKeepsSetting.ADMINS
  )

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
