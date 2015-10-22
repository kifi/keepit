package com.keepit.payments

import java.math.{ BigDecimal, RoundingMode, MathContext }

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ PaidPlanFactory, Organization, UserFactory }
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

  private def computePartialCost(fullCost: DollarAmount, cycleStart: DateTime, billingCycle: BillingCycle): DollarAmount = {
    val MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_DOWN)
    val cycleEnd: DateTime = cycleStart.plusMonths(billingCycle.month)
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
        val accountId = Id[PaidAccount](1)

        val (userId, userIdToo) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val userToo = UserFactory.user().saved
          (user.id.get, userToo.id.get)
        }

        //not using a factory here because I want manual control over the account initialization (a few lines down)
        val orgId = Id[Organization](1)

        val (account, plan) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(planPrice).withBillingCycle(billingCycle).saved
          commander.createAndInitializePaidAccountForOrganization(orgId, plan.id.get, userId, session)
          val account = accountRepo.get(accountId)
          account.activeUsers === 1
          account.userContacts === Seq(userId)
          account.credit === DollarAmount.dollars(50) - computePartialCost(planPrice, account.billingCycleStart, billingCycle)
          account.lockedForProcessing === false

          //"fast forward" to test proration
          accountRepo.save(account.copy(billingCycleStart = account.billingCycleStart.minusDays(12), planRenewal = account.planRenewal.minusDays(12)))

          (account, plan)
        }

        commander.registerNewUser(orgId, userIdToo, actionAttribution)

        val currentCredit = db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(accountId)
          updatedAccount.activeUsers === 2
          updatedAccount.credit === account.credit - computePartialCost(planPrice, updatedAccount.billingCycleStart, billingCycle)
          updatedAccount.credit
        }

        commander.grantSpecialCredit(orgId, -currentCredit, None, None, None)

        db.readOnlyMaster { implicit session =>
          accountRepo.get(accountId).credit === DollarAmount.dollars(0)
        }

        val setCredit = DollarAmount.dollars(-25)
        commander.grantSpecialCredit(orgId, setCredit, None, None, None)

        db.readOnlyMaster { implicit session =>
          accountRepo.get(accountId).credit === setCredit
        }

        val freePlan = db.readWrite { implicit session =>
          PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.dollars(0)).withBillingCycle(billingCycle).saved
        }

        commander.changePlan(orgId, freePlan.id.get, actionAttribution)

        val accountOnFreePlan = db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(accountId)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === freePlan.id.get
          updatedAccount.credit === setCredit + DollarAmount(2 * computePartialCost(planPrice, updatedAccount.billingCycleStart, billingCycle).cents)
          updatedAccount
        }

        //"fast forward" again to test proration of plan change cost
        db.readWrite { implicit session =>
          val currentAccount = accountRepo.get(accountId)
          accountRepo.save(currentAccount.copy(billingCycleStart = currentAccount.billingCycleStart.minusDays(5), planRenewal = account.planRenewal.minusDays(5)))
        }

        commander.changePlan(orgId, plan.id.get, actionAttribution)

        db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(accountId)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === plan.id.get
          updatedAccount.credit === accountOnFreePlan.credit - DollarAmount(2 * computePartialCost(planPrice, updatedAccount.billingCycleStart, billingCycle).cents)
          updatedAccount.paymentStatus === PaymentStatus.Ok
        }

      }
    }
  }

}
