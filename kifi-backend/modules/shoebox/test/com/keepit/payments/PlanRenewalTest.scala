package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.model.{ PaidAccountFactory, PaidPlanFactory, OrganizationFactory, UserFactory }
import com.keepit.model.PaidPlanFactoryHelper.PaidPlanPersister
import com.keepit.model.PaidAccountFactoryHelper.PaidAccountPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.payments.AccountEventAction.PlanBilling
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class PlanRenewalTest extends SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  "PaidAccountRepo" should {
    "get renewable accounts" in {
      withDb(modules: _*) { implicit injector =>
        val accountRepo = inject[PaidAccountRepo]
        db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val monthlyPlan = PaidPlanFactory.paidPlan().withBillingCycle(BillingCycle(1)).saved
          val yearlyPlan = PaidPlanFactory.paidPlan().withBillingCycle(BillingCycle(12)).saved
          val (kifiId, kifiAccount) = {
            val kifiOrg = OrganizationFactory.organization().withOwner(user).withName("Kifi").saved
            (kifiOrg.id.get, PaidAccountFactory.paidAccount().withOrganization(kifiOrg).withPlan(monthlyPlan.id.get).withBillingCycleStart(currentDateTime).saved)
          }

          val (googleId, googleAccount) = {
            val googleOrg = OrganizationFactory.organization().withOwner(user).withName("Google").saved
            (googleOrg.id.get, PaidAccountFactory.paidAccount().withOrganization(googleOrg).withPlan(monthlyPlan.id.get).withBillingCycleStart(currentDateTime).saved)
          }

          accountRepo.getRenewable() === Seq()

          val renewableGoogle = accountRepo.save(googleAccount.withCycleStart(currentDateTime minusMonths 2).withPlanRenewal(currentDateTime minusMonths 1))
          accountRepo.getRenewable() === Seq(renewableGoogle)

          val renewableKifi = accountRepo.save(kifiAccount.withCycleStart(currentDateTime minusMonths 1).withPlanRenewal(currentDateTime))
          accountRepo.getRenewable() === Seq(renewableGoogle, renewableKifi)

          val yearlyGoogle = accountRepo.save(renewableGoogle.withNewPlan(yearlyPlan.id.get))
          accountRepo.getRenewable() === Seq(renewableKifi)
        }

      }
    }
  }

  "PlanRenewalCommander" should {
    "refuse to process a frozen account, no matter what" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PlanRenewalCommanderImpl]
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.dollars(10)).withBillingCycle(BillingCycle(1)).saved
          PaidAccountFactory.paidAccount()
            .withOrganizationId(org.id.get)
            .withPlan(plan.id.get)
            .withFrozen(true)
            .withCredit(DollarAmount.ZERO)
            .withBillingCycleStart(currentDateTime.minusMonths(1).minusDays(1))
            .saved
        }
        commander.renewPlan(accountPre) should beAFailedTry(FrozenAccountException(accountPre.orgId))

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount === accountPre
        }
      }
    }

    "refuse to renew an account's plan before its renewal date" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PlanRenewalCommanderImpl]
        val now = currentDateTime
        val billingCycleStart = now.minusMonths(1).plusDays(1)
        val billingCycle = BillingCycle(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.dollars(10)).withBillingCycle(billingCycle).saved
          PaidAccountFactory.paidAccount()
            .withOrganizationId(org.id.get)
            .withPlan(plan.id.get)
            .withCredit(DollarAmount.ZERO)
            .withBillingCycleStart(billingCycleStart)
            .saved
        }

        val renewsAt = billingCycleStart plusMonths billingCycle.month
        commander.renewPlan(accountPre) should beAFailedTry(UnexpectedPlanRenewalException(accountPre.orgId, renewsAt))

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount === accountPre
        }
      }
    }

    "properly renew an account's paid plan passed its renewal date" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PlanRenewalCommanderImpl]
        val now = currentDateTime
        val billingCycleStart = now.minusMonths(1).minusDays(1)
        val billingCycle = BillingCycle(1)
        val price = DollarAmount.dollars(10)
        val activeUsers = 5
        val (accountPre, plan) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(billingCycle).saved
          val account = PaidAccountFactory.paidAccount()
            .withOrganizationId(org.id.get)
            .withPlan(plan.id.get)
            .withCredit(DollarAmount.ZERO)
            .withBillingCycleStart(billingCycleStart)
            .withPlanRenewal(billingCycleStart plusMonths billingCycle.month)
            .withActiveUsers(activeUsers)
            .saved
          (account, plan)
        }

        val renewalCost = plan.pricePerCyclePerUser * accountPre.activeUsers
        renewalCost should beGreaterThan(DollarAmount.ZERO)

        val renewal = commander.renewPlan(accountPre)
        renewal should beASuccessfulTry

        renewal.get.action === PlanBilling(plan.id.get, plan.billingCycle, plan.pricePerCyclePerUser, accountPre.activeUsers, billingCycleStart)
        renewal.get.creditChange === -renewalCost

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.billingCycleStart === (accountPre.billingCycleStart plusMonths billingCycle.month)
          updatedAccount.planRenewal === (accountPre.planRenewal plusMonths billingCycle.month)
          updatedAccount.credit === (accountPre.credit - renewalCost)
          updatedAccount.paymentDueAt should beSome
          updatedAccount.paymentDueAt.get should beLessThan(currentDateTime)
        }
      }
    }

    "do not update paymentDueAt when renewing a free plan" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PlanRenewalCommanderImpl]
        val now = currentDateTime
        val billingCycleStart = now.minusMonths(1).minusDays(1)
        val billingCycle = BillingCycle(1)
        val price = DollarAmount.ZERO // free plan
        val activeUsers = 5
        val paymentDueAt = now plusDays 10 // arbitrary
        val (accountPre, plan) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(billingCycle).saved
          val account = PaidAccountFactory.paidAccount()
            .withOrganizationId(org.id.get)
            .withPlan(plan.id.get)
            .withCredit(DollarAmount.ZERO)
            .withActiveUsers(activeUsers)
            .withBillingCycleStart(billingCycleStart)
            .withPaymentDueAt(paymentDueAt)
            .saved
          (account, plan)
        }

        val renewalCost = plan.pricePerCyclePerUser * accountPre.activeUsers
        renewalCost === DollarAmount.ZERO

        val renewal = commander.renewPlan(accountPre)
        renewal should beASuccessfulTry

        renewal.get.action === PlanBilling(plan.id.get, plan.billingCycle, plan.pricePerCyclePerUser, accountPre.activeUsers, billingCycleStart)
        renewal.get.creditChange === -renewalCost

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.billingCycleStart === (accountPre.billingCycleStart plusMonths billingCycle.month)
          updatedAccount.planRenewal === (accountPre.planRenewal plusMonths billingCycle.month)
          updatedAccount.credit === (accountPre.credit - renewalCost)
          updatedAccount.paymentDueAt === accountPre.paymentDueAt
        }
      }
    }
  }
}
