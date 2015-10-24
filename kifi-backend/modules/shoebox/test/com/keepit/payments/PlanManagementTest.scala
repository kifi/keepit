package com.keepit.payments

import java.math.{ BigDecimal, RoundingMode, MathContext }

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ OrganizationFactory, PaidPlanFactory, Organization, UserFactory }
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

  private def computePartialCost(fullCost: DollarAmount, renewalDate: DateTime, billingCycle: BillingCycle): DollarAmount = {
    val MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_DOWN)
    val cycleStart: DateTime = renewalDate minusMonths billingCycle.month
    val cycleEnd: DateTime = renewalDate
    val cycleLengthDays: Double = Days.daysBetween(cycleStart, cycleEnd).getDays.toDouble
    val remaining: Double = Days.daysBetween(currentDateTime, cycleEnd).getDays.toDouble
    val fraction: Double = remaining / cycleLengthDays
    val fullPrice = new BigDecimal(fullCost.cents, MATH_CONTEXT)
    val remainingPrice = fullPrice.multiply(new BigDecimal(fraction, MATH_CONTEXT), MATH_CONTEXT).setScale(0, RoundingMode.HALF_DOWN)
    DollarAmount(remainingPrice.intValueExact)
  }

  "PlanManagementCommander" should {
    "correctly adjust credit for added/removed users, special credit and changed plans" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PlanManagementCommander]
        val accountRepo = inject[PaidAccountRepo]
        val planPrice = DollarAmount.dollars(10)
        val billingCycle = BillingCycle(1)
        val actionAttribution = ActionAttribution(None, None)

        val (owner, user) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val user = UserFactory.user().saved
          (owner, user)
        }

        val (org, account, plan) = db.readWrite { implicit session =>
          val org = orgRepo.save(OrganizationFactory.organization().withOwner(owner).org) // explicitly not using the OrgFactoryHelper
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(planPrice).withBillingCycle(billingCycle).saved
          val createEvent = commander.createAndInitializePaidAccountForOrganization(org.id.get, plan.id.get, owner.id.get, session).get
          val account = accountRepo.get(createEvent.accountId)
          account.activeUsers === 1
          account.userContacts === Seq(owner.id.get)
          account.credit === DollarAmount.dollars(50) - computePartialCost(planPrice, account.planRenewal, billingCycle)
          account.lockedForProcessing === false

          //"fast forward" to test proration
          accountRepo.save(account.copy(planRenewal = account.planRenewal.minusDays(12)))

          (org, account, plan)
        }

        commander.registerNewUser(org.id.get, user.id.get, actionAttribution)

        val currentCredit = db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.credit === account.credit - computePartialCost(planPrice, updatedAccount.planRenewal, billingCycle)
          updatedAccount.credit
        }

        commander.grantSpecialCredit(org.id.get, -currentCredit, None, None, None)

        db.readOnlyMaster { implicit session =>
          accountRepo.get(account.id.get).credit === DollarAmount.dollars(0)
        }

        val setCredit = DollarAmount.dollars(-25)
        commander.grantSpecialCredit(org.id.get, setCredit, None, None, None)

        db.readOnlyMaster { implicit session =>
          accountRepo.get(account.id.get).credit === setCredit
        }

        val freePlan = db.readWrite { implicit session =>
          PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.dollars(0)).withBillingCycle(billingCycle).saved
        }

        commander.changePlan(org.id.get, freePlan.id.get, actionAttribution)

        val accountOnFreePlan = db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === freePlan.id.get
          updatedAccount.credit === setCredit + DollarAmount(2 * computePartialCost(planPrice, updatedAccount.planRenewal, billingCycle).cents)
          updatedAccount
        }

        //"fast forward" again to test proration of plan change cost
        db.readWrite { implicit session =>
          val currentAccount = accountRepo.get(account.id.get)
          accountRepo.save(currentAccount.copy(planRenewal = account.planRenewal.minusDays(5)))
        }

        commander.changePlan(org.id.get, plan.id.get, actionAttribution)

        db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(account.id.get)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === plan.id.get
          updatedAccount.credit === accountOnFreePlan.credit - DollarAmount(2 * computePartialCost(planPrice, updatedAccount.planRenewal, billingCycle).cents)
          updatedAccount.paymentStatus === PaymentStatus.Ok
        }

      }
    }
  }

}
