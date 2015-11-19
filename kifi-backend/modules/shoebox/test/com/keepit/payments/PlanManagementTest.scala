package com.keepit.payments

import java.math.{ BigDecimal, RoundingMode, MathContext }

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.common.util.DollarAmount
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.{ Days, DateTime }
import org.specs2.mutable.SpecificationLike
import com.keepit.model.PaidPlanFactoryHelper.PaidPlanPersister
import com.keepit.model.UserFactoryHelper.UserPersister

class PlanManagementTest extends SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  private def computePartialCost(from: DateTime, renewalDate: DateTime, billingCycle: BillingCycle, fullCost: DollarAmount): DollarAmount = {
    val MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_DOWN)
    val cycleStart: DateTime = renewalDate minusMonths billingCycle.months
    val cycleEnd: DateTime = renewalDate
    val cycleLengthDays: Double = Days.daysBetween(cycleStart, cycleEnd).getDays.toDouble
    val remaining: Double = Days.daysBetween(from, cycleEnd).getDays.toDouble max 0
    val fraction: Double = remaining / cycleLengthDays
    val fullPrice = new BigDecimal(fullCost.cents, MATH_CONTEXT)
    val remainingPrice = fullPrice.multiply(new BigDecimal(fraction, MATH_CONTEXT), MATH_CONTEXT).setScale(0, RoundingMode.HALF_DOWN)
    DollarAmount(remainingPrice.intValueExact)
  }

  private def commander()(implicit injector: Injector) = inject[PlanManagementCommanderImpl]

  private def setup()(implicit injector: Injector): (Organization, AccountEvent) = {
    val planPrice = DollarAmount.dollars(10)
    val billingCycle = BillingCycle(1)
    db.readWrite { implicit session =>
      val owner = UserFactory.user().saved
      val org = orgRepo.save(OrganizationFactory.organization().withOwner(owner).org) // explicitly not using the OrgFactoryHelper
      val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(planPrice).withBillingCycle(billingCycle).saved
      val createEvent = commander.createAndInitializePaidAccountForOrganization(org.id.get, plan.id.get, owner.id.get, session).get
      (org, createEvent)
    }
  }

  private val noAttribution = ActionAttribution(None, None)

  "PlanManagementCommander" should {

    "start new plans at 8PM UTC - 1PM PST" in {
      withDb(modules: _*) { implicit injector =>
        PlanRenewalPolicy.newPlansStartDate(parseStandardTime("2015-10-23 18:56:21.000 -0000")) === parseStandardTime("2015-10-23 20:00:00.000 -0000")
        PlanRenewalPolicy.newPlansStartDate(parseStandardTime("2015-10-23 21:00:00.000 -0000")) === parseStandardTime("2015-10-24 20:00:00.000 -0000")
      }
    }

    "correctly create initial account" in {
      withDb(modules: _*) { implicit injector =>
        val (org, createEvent) = setup()
        val account = db.readOnlyMaster { implicit session =>
          paidAccountRepo.getByOrgId(org.id.get)
        }

        createEvent.accountId === account.id.get
        createEvent.action === AccountEventAction.OrganizationCreated(account.planId, Some(account.planRenewal))
        createEvent.whoDunnit === Some(org.ownerId)

        account.activeUsers === 1
        account.userContacts === Seq(org.ownerId)
        account.credit === DollarAmount.dollars(50)
        account.lockedForProcessing === false
        account.planRenewal === PlanRenewalPolicy.newPlansStartDate(currentDateTime)
      }
    }

    "correctly compute partial costs" in {
      withDb(modules: _*) { implicit injector =>
        val (org, _) = setup()
        val (account, plan) = db.readOnlyMaster { implicit session =>
          val account = paidAccountRepo.getByOrgId(org.id.get)
          (account, paidPlanRepo.get(account.planId))
        }
        plan.pricePerCyclePerUser should beGreaterThan(DollarAmount.ZERO)
        db.readOnlyMaster { implicit session =>
          commander.remainingBillingCycleCostPerUser(account, account.planRenewal minusDays 5) === computePartialCost(account.planRenewal minusDays 5, account.planRenewal, plan.billingCycle, plan.pricePerCyclePerUser)
          commander.remainingBillingCycleCostPerUser(account, account.planRenewal) === DollarAmount.ZERO
          commander.remainingBillingCycleCostPerUser(account, account.planRenewal plusDays 5) === DollarAmount.ZERO
        }
      }
    }

    "correctly adjust credit for added/removed users" in {
      withDb(modules: _*) { implicit injector =>

        val (org, _) = setup()

        val (plan, account, userId) = db.readWrite { implicit session =>
          val account = {
            val accountWithNextDayRenewal = paidAccountRepo.getByOrgId(org.id.get)
            paidAccountRepo.save(accountWithNextDayRenewal.copy(planRenewal = accountWithNextDayRenewal.planRenewal.plusDays(12))) // Set future renewal date to test partial costs / refunds
          }
          val plan = paidPlanRepo.get(account.planId)
          (plan, account, UserFactory.user().saved.id.get)
        }

        plan.pricePerCyclePerUser should beGreaterThan(DollarAmount.ZERO)

        val expectedCost = computePartialCost(currentDateTime, account.planRenewal, plan.billingCycle, plan.pricePerCyclePerUser)
        expectedCost should beGreaterThan(DollarAmount.ZERO)
        val added = db.readWrite { implicit session => commander.registerNewUser(org.id.get, userId, OrganizationRole.MEMBER, noAttribution) }
        added.action === AccountEventAction.UserJoinedOrganization(userId, OrganizationRole.MEMBER)
        added.creditChange === -expectedCost

        val accountWithTwoUsers = db.readOnlyMaster { implicit session =>
          val updatedAccount = paidAccountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.credit === account.credit - expectedCost
          updatedAccount
        }

        val expectedRefund = computePartialCost(currentDateTime, account.planRenewal, plan.billingCycle, plan.pricePerCyclePerUser)
        expectedRefund should beGreaterThan(DollarAmount.ZERO)
        val removed = db.readWrite { implicit session => commander.registerRemovedUser(org.id.get, userId, OrganizationRole.MEMBER, noAttribution) }
        removed.action === AccountEventAction.UserLeftOrganization(userId, OrganizationRole.MEMBER)
        removed.creditChange === expectedRefund

        db.readOnlyMaster { implicit session =>
          val updatedAccount = paidAccountRepo.get(account.id.get)
          updatedAccount.activeUsers === 1
          updatedAccount.credit === accountWithTwoUsers.credit + expectedRefund
        }
      }
    }

    "correctly change plans" in {
      withDb(modules: _*) { implicit injector =>

        val (org, _) = setup()

        val (account, paidPlan, freePlan) = db.readWrite { implicit session =>
          // test with two users and "fast forward" to test proration of plan change cost
          val userId = UserFactory.user().saved.id.get
          commander.registerNewUser(org.id.get, userId, OrganizationRole.MEMBER, noAttribution)
          val account = paidAccountRepo.save(paidAccountRepo.getByOrgId(org.id.get).withPlanRenewal(currentDateTime plusDays 15))

          val paidPlan = paidPlanRepo.get(account.planId)
          paidPlan.pricePerCyclePerUser should beGreaterThan(DollarAmount.ZERO)

          val freePlan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.dollars(0)).withBillingCycle(BillingCycle.months(1)).saved

          (account, paidPlan, freePlan)
        }

        // Cannot change to current plan
        commander.changePlan(org.id.get, paidPlan.id.get, noAttribution) should beAFailedTry(InvalidChange("plan_already_selected"))

        // Correctly change to free plan with proper refund
        val expectedFreePlanStartDate = PlanRenewalPolicy.newPlansStartDate(currentDateTime)
        val expectedRefund = computePartialCost(expectedFreePlanStartDate, account.planRenewal, paidPlan.billingCycle, paidPlan.pricePerCyclePerUser) * 2
        expectedRefund should beGreaterThan(DollarAmount.ZERO)

        val changeToFreePlanMaybe = commander.changePlan(org.id.get, freePlan.id.get, noAttribution)
        changeToFreePlanMaybe.map(_.action) should beASuccessfulTry(AccountEventAction.PlanChanged(paidPlan.id.get, freePlan.id.get, Some(expectedFreePlanStartDate)))
        changeToFreePlanMaybe.get.creditChange === expectedRefund

        val accountOnFreePlan = db.readOnlyMaster { implicit session =>
          val updatedAccount = paidAccountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === freePlan.id.get
          updatedAccount.credit === account.credit + expectedRefund
          updatedAccount
        }

        // Changing back and forth does not trigger repeated refunds
        computePartialCost(PlanRenewalPolicy.newPlansStartDate(currentDateTime), accountOnFreePlan.planRenewal, freePlan.billingCycle, freePlan.pricePerCyclePerUser) * 1 === DollarAmount.ZERO
        commander.changePlan(org.id.get, paidPlan.id.get, noAttribution).map(_.creditChange) should beASuccessfulTry(DollarAmount.ZERO) // this could be because the plan is free

        val accountOnPaidPlanAgain = db.readOnlyMaster { implicit session =>
          val updatedAccount = paidAccountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === paidPlan.id.get
          updatedAccount.credit === accountOnFreePlan.credit
          updatedAccount
        }

        computePartialCost(PlanRenewalPolicy.newPlansStartDate(currentDateTime), accountOnPaidPlanAgain.planRenewal, paidPlan.billingCycle, paidPlan.pricePerCyclePerUser) * 1 === DollarAmount.ZERO
        commander.changePlan(org.id.get, freePlan.id.get, noAttribution).map(_.creditChange) should beASuccessfulTry(DollarAmount.ZERO) // this is because new plans does not renew before the next day

        db.readOnlyMaster { implicit session =>
          val updatedAccount = paidAccountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === freePlan.id.get
          updatedAccount.credit === accountOnPaidPlanAgain.credit
        }
      }
    }
  }
}
