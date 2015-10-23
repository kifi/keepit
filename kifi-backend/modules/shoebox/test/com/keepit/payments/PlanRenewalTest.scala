package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.model.{ PaidAccountFactory, PaidPlanFactory, OrganizationFactory, UserFactory }
import com.keepit.model.PaidPlanFactoryHelper.PaidPlanPersister
import com.keepit.model.PaidAccountFactoryHelper.PaidAccountPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
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
        val now = currentDateTime
        db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val monthlyPlan = PaidPlanFactory.paidPlan().withBillingCycle(BillingCycle(1)).saved
          val (kifiId, kifiAccount) = {
            val kifiOrg = OrganizationFactory.organization().withOwner(user).withName("Kifi").saved
            (kifiOrg.id.get, PaidAccountFactory.paidAccount().withOrganization(kifiOrg).withPlan(monthlyPlan.id.get).withPlanRenewal(now plusMonths 1).saved)
          }

          val (googleId, googleAccount) = {
            val googleOrg = OrganizationFactory.organization().withOwner(user).withName("Google").saved
            (googleOrg.id.get, PaidAccountFactory.paidAccount().withOrganization(googleOrg).withPlan(monthlyPlan.id.get).withPlanRenewal(now plusMonths 1).saved)
          }

          accountRepo.getRenewable() === Seq()

          val renewableGoogle = accountRepo.save(googleAccount.withPlanRenewal(now minusMonths 2))
          accountRepo.getRenewable() === Seq(renewableGoogle)

          val renewableKifi = accountRepo.save(kifiAccount.withPlanRenewal(now minusMonths 1))
          accountRepo.getRenewable() === Seq(renewableGoogle, renewableKifi)

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
            .withPlanRenewal(currentDateTime minusDays 7)
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
        val billingCycle = BillingCycle(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(DollarAmount.dollars(10)).withBillingCycle(billingCycle).saved
          PaidAccountFactory.paidAccount()
            .withOrganizationId(org.id.get)
            .withPlan(plan.id.get)
            .withCredit(DollarAmount.ZERO)
            .withPlanRenewal(currentDateTime plusDays 7)
            .saved
        }

        commander.renewPlan(accountPre) should beAFailedTry(UnnecessaryPlanRenewalException(accountPre))

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
            .withPlanRenewal(currentDateTime minusDays 7)
            .withActiveUsers(activeUsers)
            .saved
          (account, plan)
        }

        val renewalCost = plan.pricePerCyclePerUser * accountPre.activeUsers
        renewalCost should beGreaterThan(DollarAmount.ZERO)

        val renewal = commander.renewPlan(accountPre)
        renewal should beASuccessfulTry

        renewal.get.action === AccountEventAction.PlanRenewal(plan.id.get, plan.billingCycle, plan.pricePerCyclePerUser, accountPre.activeUsers, accountPre.planRenewal)
        renewal.get.creditChange === -renewalCost

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
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
        val billingCycle = BillingCycle(1)
        val price = DollarAmount.ZERO // free plan
        val activeUsers = 5
        val paymentDueAt = currentDateTime plusDays 10 // arbitrary
        val (accountPre, plan) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(billingCycle).saved
          val account = PaidAccountFactory.paidAccount()
            .withOrganizationId(org.id.get)
            .withPlan(plan.id.get)
            .withCredit(DollarAmount.ZERO)
            .withActiveUsers(activeUsers)
            .withPlanRenewal(currentDateTime minusDays 7)
            .withPaymentDueAt(paymentDueAt)
            .saved
          (account, plan)
        }

        val renewalCost = plan.pricePerCyclePerUser * accountPre.activeUsers
        renewalCost === DollarAmount.ZERO

        val renewal = commander.renewPlan(accountPre)
        renewal should beASuccessfulTry

        renewal.get.action === AccountEventAction.PlanRenewal(plan.id.get, plan.billingCycle, plan.pricePerCyclePerUser, accountPre.activeUsers, accountPre.planRenewal)
        renewal.get.creditChange === -renewalCost

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.planRenewal === (accountPre.planRenewal plusMonths billingCycle.month)
          updatedAccount.credit === (accountPre.credit - renewalCost)
          updatedAccount.paymentDueAt === accountPre.paymentDueAt
        }
      }
    }
  }
}
