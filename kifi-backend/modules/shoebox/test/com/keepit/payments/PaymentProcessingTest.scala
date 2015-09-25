package com.keepit.payments

import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.model.{ PaidPlanFactory, Organization, User, PaidAccountFactory, OrganizationFactory, UserFactory }
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.PaidPlanFactoryHelper.PaidPlanPersister
import com.keepit.model.PaidAccountFactoryHelper.PaidAccountPersister
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister

import org.specs2.mutable.SpecificationLike

import org.joda.time.{ DateTime, Days }

import java.math.{ BigDecimal, MathContext, RoundingMode }

import scala.concurrent.Await

import scala.concurrent.duration.Duration

//things to test: add_user, remvove_user, change plan free to paid, all paths through charge processing

class PaymentProcessingTest extends SpecificationLike with ShoeboxTestInjector {

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
        val planPrice = DollarAmount.wholeDollars(10)
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
          account.credit === computePartialCost(planPrice, account.billingCycleStart, billingCycle).negative
          account.lockedForProcessing === false

          //"fast forward" to test proration
          accountRepo.save(account.copy(billingCycleStart = account.billingCycleStart.minusDays(12)))

          (account, plan)
        }

        commander.registerRemovedUser(orgId, userId, actionAttribution)

        db.readWrite { implicit session =>
          val accountFromThePast = accountRepo.get(accountId)
          accountFromThePast.activeUsers === 0
          accountFromThePast.credit === account.credit + computePartialCost(planPrice, accountFromThePast.billingCycleStart, billingCycle)
        }

        commander.registerNewUser(orgId, userId, actionAttribution)
        commander.registerNewUser(orgId, userIdToo, actionAttribution)

        val currentCredit = db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(accountId)
          updatedAccount.activeUsers === 2
          updatedAccount.credit === account.credit + computePartialCost(planPrice, updatedAccount.billingCycleStart, billingCycle).negative
          updatedAccount.credit
        }

        currentCredit.cents must be_<(0)
        commander.grantSpecialCredit(orgId, currentCredit.negative, None, None)

        db.readOnlyMaster { implicit session =>
          accountRepo.get(accountId).credit === DollarAmount.wholeDollars(0)
        }

        val setCredit = DollarAmount.wholeDollars(-25)
        commander.grantSpecialCredit(orgId, setCredit, None, None)

        db.readOnlyMaster { implicit session =>
          accountRepo.get(accountId).credit === setCredit
        }

        val freePlan = db.readWrite { implicit session =>
          PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.wholeDollars(0)).withBillingCycle(billingCycle).saved
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
          accountRepo.save(currentAccount.copy(billingCycleStart = currentAccount.billingCycleStart.minusDays(5)))
        }

        commander.changePlan(orgId, plan.id.get, actionAttribution)

        db.readOnlyMaster { implicit session =>
          val updatedAccount = accountRepo.get(accountId)
          updatedAccount.activeUsers === 2
          updatedAccount.planId === plan.id.get
          updatedAccount.credit === accountOnFreePlan.credit + DollarAmount(2 * computePartialCost(planPrice, updatedAccount.billingCycleStart, billingCycle).cents).negative
        }

      }
    }
  }

  "PaymentProcessingCommander" should {
    "do nothing if account is frozen, no matter what" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount(438)
        val initialCredit = commander.MIN_BALANCE + DollarAmount.wholeDollars(1).negative
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          PaidAccountFactory.paidAccount()
            .withOrganization(org.id.get)
            .withPlan(plan.id.get)
            .withFrozen(true)
            .withCredit(initialCredit)
            .withBillingCycleStart(currentDateTime.minusMonths(1).minusDays(1))
            .saved
        }
        val (charge, message) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        charge === DollarAmount.ZERO
        message === "Not processed because conditions not met"

        db.readOnlyMaster { implicit session =>
          inject[PaidAccountRepo].get(accountPre.id.get).credit === initialCredit
        }

      }
    }

    "do not charge if balance is too low (with elapsed billing cycle)" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount(438)
        val initialCredit = commander.MIN_BALANCE + DollarAmount(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          PaidAccountFactory.paidAccount().withPlan(plan.id.get).withOrganization(org.id.get)
            .withCredit(initialCredit)
            .withBillingCycleStart(currentDateTime.minusMonths(1).minusDays(1))
            .saved
        }
        val (charge, message) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        charge === DollarAmount.ZERO
        message === "Not charging because of low balance"

        db.readOnlyMaster { implicit session =>
          inject[PaidAccountRepo].get(accountPre.id.get).credit === initialCredit
        }

      }
    }

    "do not charge if billing cycle has not elapsed && max balance is not exceeded" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount(438)
        val initialCredit = commander.MAX_BALANCE + DollarAmount(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          PaidAccountFactory.paidAccount().withPlan(plan.id.get).withOrganization(org.id.get)
            .withBillingCycleStart(currentDateTime)
            .withCredit(initialCredit)
            .saved
        }
        val (charge, message) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        charge === DollarAmount.ZERO
        message === "Not processed because conditions not met"

        db.readOnlyMaster { implicit session =>
          inject[PaidAccountRepo].get(accountPre.id.get).credit === initialCredit
        }
      }
    }

    "do charge when billing cycle has elapsed && min balance is exceeded (with correct charge amount returned, correct new credit, and correct new billing cycle start)" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val stripeClient = inject[StripeClient].asInstanceOf[FakeStripeClientImpl]
        val price = DollarAmount(438)
        val initialCredit = commander.MIN_BALANCE + DollarAmount.wholeDollars(1).negative
        val billingCycleStart = currentDateTime.minusMonths(1).minusDays(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved

          val account = PaidAccountFactory.paidAccount().withPlan(plan.id.get).withOrganization(org.id.get)
            .withBillingCycleStart(billingCycleStart)
            .withCredit(initialCredit)
            .withActiveUsers(3)
            .saved

          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(stripeClient.getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))

          account
        }

        val (charge, message) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        charge === DollarAmount(3 * price.cents) + initialCredit.negative
        message === "Charge performed"

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.credit === DollarAmount.ZERO
          updatedAccount.billingCycleStart === billingCycleStart.plusMonths(1)
        }

      }
    }

    "do charge when max balance has been exceeded but billing cylcle not elapsed (with correct charge amount returned, correct new credit, and no new billing cycle start)" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val stripeClient = inject[StripeClient].asInstanceOf[FakeStripeClientImpl]
        val price = DollarAmount(438)
        val billingCycleStart = currentDateTime
        val initialCredit = commander.MAX_BALANCE + DollarAmount.wholeDollars(7).negative
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          val account = PaidAccountFactory.paidAccount().withPlan(plan.id.get).withOrganization(org.id.get)
            .withBillingCycleStart(billingCycleStart)
            .withCredit(initialCredit)
            .withActiveUsers(3)
            .saved

          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(stripeClient.getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))

          account
        }

        val (charge, message) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        charge === initialCredit.negative
        message === "Charge performed"

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.credit === DollarAmount.ZERO
          updatedAccount.billingCycleStart === billingCycleStart
        }

      }
    }

    "do not updated billing cycle (when elapsed) or credit when there is a stripe failure" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val stripeClient = inject[StripeClient].asInstanceOf[FakeStripeClientImpl]
        stripeClient.failingMode = true
        val price = DollarAmount(438)
        val initialCredit = commander.MAX_BALANCE + DollarAmount.wholeDollars(7).negative
        val billingCycleStart = currentDateTime.minusMonths(1).minusDays(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved

          val account = PaidAccountFactory.paidAccount().withPlan(plan.id.get).withOrganization(org.id.get)
            .withBillingCycleStart(billingCycleStart)
            .withCredit(initialCredit)
            .withActiveUsers(3)
            .saved

          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(stripeClient.getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))

          account
        }

        Await.result(commander.processAccount(accountPre), Duration.Inf) should throwA[Exception](message = "boom")

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.credit === initialCredit
          updatedAccount.billingCycleStart === billingCycleStart
        }

      }
    }

    "do not updated billing cycle (when elapsed) or credit when there is no payment method on file" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount(438)
        val initialCredit = commander.MAX_BALANCE + DollarAmount.wholeDollars(7).negative
        val billingCycleStart = currentDateTime.minusMonths(1).minusDays(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved

          PaidAccountFactory.paidAccount().withPlan(plan.id.get).withOrganization(org.id.get)
            .withBillingCycleStart(billingCycleStart)
            .withCredit(initialCredit)
            .withActiveUsers(3)
            .saved

        }

        Await.result(commander.processAccount(accountPre), Duration.Inf) should throwA[Exception](message = "missing_default_payment_method")

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.credit === initialCredit
          updatedAccount.billingCycleStart === billingCycleStart
        }

      }
    }

  }

}
