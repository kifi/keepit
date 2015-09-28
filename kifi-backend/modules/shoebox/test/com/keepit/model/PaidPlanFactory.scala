package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.payments._
import org.apache.commons.lang3.RandomStringUtils.random

object PaidPlanFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  val testPlanFeatures: Set[PlanFeature] =
    Set( // very hard-coded creation of a plan feature set to ensure that our testing feature set matches existing features
      PlanFeature(OrganizationPermissionFeature.PublishLibraries.name, OrganizationPermissionFeature.PublishLibraries.options.find(_ == "member").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.InviteMembers.name, OrganizationPermissionFeature.InviteMembers.options.find(_ == "member").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.GroupMessaging.name, OrganizationPermissionFeature.GroupMessaging.options.find(_ == "member").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.EditLibrary.name, OrganizationPermissionFeature.EditLibrary.options.find(_ == "disabled").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.ViewMembers.name, OrganizationPermissionFeature.ViewMembers.options.find(_ == "anyone").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.RemoveOrganizationLibraries.name, OrganizationPermissionFeature.RemoveOrganizationLibraries.options.find(_ == "member").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.CreateSlackIntegration.name, OrganizationPermissionFeature.CreateSlackIntegration.options.find(_ == "disabled").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.EditOrganization.name, OrganizationPermissionFeature.EditOrganization.options.find(_ == "admin").get, editable = true),
      PlanFeature(OrganizationPermissionFeature.ExportKeeps.name, OrganizationPermissionFeature.ExportKeeps.options.find(_ == "admin").get, editable = true)
    )

  def paidPlan(): PartialPaidPlan = {
    assert(testPlanFeatures.map(_.name) == Feature.ALL.map(_.name), "test feature set is missing a feature")
    new PartialPaidPlan(PaidPlan(id = Some(Id[PaidPlan](idx.incrementAndGet())), kind = PaidPlan.Kind.NORMAL, name = Name(random(5)),
      pricePerCyclePerUser = DollarAmount(10000), billingCycle = BillingCycle(month = 1), features = testPlanFeatures))
  }

  def paidPlans(count: Int): Seq[PartialPaidPlan] = List.fill(count)(paidPlan())

  class PartialPaidPlan private[PaidPlanFactory] (plan: PaidPlan) {
    def withId(id: Id[PaidPlan]) = new PartialPaidPlan(plan.copy(id = Some(id)))
    def withId(id: Int) = new PartialPaidPlan(plan.copy(id = Some(Id[PaidPlan](id))))
    def withKind(kind: PaidPlan.Kind) = new PartialPaidPlan(plan.copy(kind = kind))
    def withName(name: Name[PaidPlan]) = new PartialPaidPlan(plan.copy(name = name))
    def withName(name: String) = new PartialPaidPlan(plan.copy(name = Name[PaidPlan](name)))
    def withPricePerCyclePerUser(pricePerCyclePerUser: DollarAmount) = new PartialPaidPlan(plan.copy(pricePerCyclePerUser = pricePerCyclePerUser))
    def withBillingCycle(billingCycle: BillingCycle) = new PartialPaidPlan(plan.copy(billingCycle = billingCycle))
    def withFeature(feature: PlanFeature) = new PartialPaidPlan(plan.copy(features = plan.features ++ Set(feature)))
    def withFeatures(features: Set[PlanFeature]) = new PartialPaidPlan(plan.copy(features = plan.features ++ features))
    def get: PaidPlan = plan
  }

  implicit class PartialPaidPlanSeq(plans: Seq[PartialPaidPlan]) {
    def get: Seq[PaidPlan] = plans.map(_.get)
  }
}
