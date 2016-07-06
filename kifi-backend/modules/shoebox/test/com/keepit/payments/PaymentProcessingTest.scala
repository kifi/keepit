package com.keepit.payments

import com.keepit.common.util.DollarAmount
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.model.{ PaidPlanFactory, OrganizationFactory, UserFactory }
import com.keepit.common.time._
import com.keepit.model.PaidPlanFactoryHelper.PaidPlanPersister
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister

import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await

import scala.concurrent.duration.Duration

//things to test: add_user, remvove_user, change plan free to paid, all paths through charge processing

class PaymentProcessingTest extends SpecificationLike with ShoeboxTestInjector {

  args(skipAll = true)

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  "PaymentProcessingCommander" should {
    "refuse to process a frozen account, no matter what" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount.cents(438)
        val initialCredit = -commander.MAX_BALANCE - DollarAmount.dollars(1)
        val accountPre = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          paidAccountRepo.save(
            paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get)
              .copy(credit = initialCredit).withPaymentDueAt(Some(currentDateTime)).freeze
          )
        }
        Await.result(commander.processAccount(accountPre), Duration.Inf) should throwA(FrozenAccountException(accountPre.orgId))

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount === accountPre
        }

      }
    }

    "do not charge if balance is too low, regardless of the account's PaymentStatus" in {
      Seq(PaymentStatus.Ok, PaymentStatus.Pending, PaymentStatus.Failed).map { paymentStatus =>
        withDb(modules: _*) { implicit injector =>
          val commander = inject[PaymentProcessingCommander]
          val price = DollarAmount.cents(438)
          val initialCredit = -commander.MIN_BALANCE + DollarAmount.cents(1)
          val (accountPre, _) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
            val account = paidAccountRepo.save(
              paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get)
                .copy(credit = initialCredit).withPaymentDueAt(Some(currentDateTime)).withPaymentStatus(paymentStatus)
            )
            (account, plan)
          }
          accountPre.owed should beLessThan(commander.MIN_BALANCE)
          accountPre.paymentStatus === paymentStatus
          val (_, event) = Await.result(commander.processAccount(accountPre), Duration.Inf)
          event.action === AccountEventAction.LowBalanceIgnored(accountPre.owed)
          event.creditChange === DollarAmount.ZERO
          event.paymentCharge === None
          event.chargeId === None

          db.readOnlyMaster { implicit session =>
            val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
            updatedAccount.paymentStatus === accountPre.paymentStatus
            updatedAccount.credit === initialCredit
            updatedAccount.paymentDueAt === None
          }
        }
      } head
    }

    "do charge correctly if balance is high enough and payment status is Ok" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount.cents(438)
        val initialCredit = -commander.MIN_BALANCE - DollarAmount.cents(1)
        val (accountPre, _) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          val account = paidAccountRepo.save(
            paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get).withPaymentDueAt(Some(currentDateTime)).withPaymentStatus(PaymentStatus.Ok).copy(credit = initialCredit)
          )
          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(inject[StripeClient].getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))
          (account, plan)
        }
        accountPre.owed should beGreaterThan(commander.MIN_BALANCE)
        accountPre.paymentStatus === PaymentStatus.Ok
        val (_, event) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        event.action === AccountEventAction.Charge()
        event.creditChange === accountPre.owed
        event.paymentCharge === Some(accountPre.owed)
        event.chargeId should beSome

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.paymentStatus === PaymentStatus.Ok
          updatedAccount.credit === DollarAmount.ZERO
          updatedAccount.paymentDueAt === None
        }
      }
    }

    "refuse to charge an account with an invalid PaymentStatus" in {
      Seq(PaymentStatus.Pending, PaymentStatus.Failed).map { paymentStatus =>
        withDb(modules: _*) { implicit injector =>
          val commander = inject[PaymentProcessingCommander]
          val price = DollarAmount.cents(438)
          val initialCredit = -commander.MIN_BALANCE - DollarAmount.cents(1)
          val (accountPre, _) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
            val account = paidAccountRepo.save(
              paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get)
                .copy(credit = initialCredit).withPaymentDueAt(Some(currentDateTime)).withPaymentStatus(paymentStatus)
            )
            (account, plan)
          }
          accountPre.owed should beGreaterThan(commander.MIN_BALANCE)
          accountPre.paymentStatus === paymentStatus
          Await.result(commander.processAccount(accountPre), Duration.Inf) should throwA(InvalidPaymentStatusException(accountPre.orgId, paymentStatus))

          db.readOnlyMaster { implicit session =>
            val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
            updatedAccount === accountPre
          }
        }
      } head
    }

    "handle a missing payment method, mark the account with PaymentStatus.failed" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount.cents(438)
        val initialCredit = -commander.MIN_BALANCE - DollarAmount.cents(1)
        val (accountPre, _) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          val account = paidAccountRepo.save(
            paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get)
              .copy(credit = initialCredit).withPaymentDueAt(Some(currentDateTime))
          )
          val paymentMethodRepo = inject[PaymentMethodRepo]
          paymentMethodRepo.getByAccountId(account.id.get).foreach { paymentMethod =>
            paymentMethodRepo.save(paymentMethod.withState(PaymentMethodStates.INACTIVE))
          }

          (account, plan)
        }
        accountPre.owed should beGreaterThan(commander.MIN_BALANCE)
        accountPre.paymentStatus === PaymentStatus.Ok
        val (_, event) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        event.action === AccountEventAction.MissingPaymentMethod()
        event.creditChange === DollarAmount.ZERO
        event.paymentCharge === None
        event.chargeId === None

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.paymentStatus === PaymentStatus.Failed
          updatedAccount.credit === accountPre.credit
          updatedAccount.paymentDueAt === accountPre.paymentDueAt
        }
      }
    }

    "handle an invalid payment method, mark the account with PaymentStatus.failed" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val stripeClient = inject[StripeClient].asInstanceOf[FakeStripeClientImpl]
        stripeClient.cardFailureMode = true
        val price = DollarAmount.cents(438)
        val initialCredit = -commander.MIN_BALANCE - DollarAmount.cents(1)
        val (accountPre, _) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          val account = paidAccountRepo.save(
            paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get)
              .copy(credit = initialCredit).withPaymentDueAt(Some(currentDateTime)).withPaymentStatus(PaymentStatus.Ok)
          )
          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(stripeClient.getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))

          (account, plan)
        }
        accountPre.owed should beGreaterThan(commander.MIN_BALANCE)
        accountPre.paymentStatus === PaymentStatus.Ok
        val (_, event) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        event.action === AccountEventAction.ChargeFailure(accountPre.owed, "boom", "boom")
        event.creditChange === DollarAmount.ZERO
        event.paymentCharge === None
        event.chargeId === None

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.paymentStatus === PaymentStatus.Failed
          updatedAccount.credit === accountPre.credit
          updatedAccount.paymentDueAt === accountPre.paymentDueAt
        }
      }
    }

    "handle Stripe being down, leave the account untouched" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val stripeClient = inject[StripeClient].asInstanceOf[FakeStripeClientImpl]
        stripeClient.stripeDownMode = true
        val price = DollarAmount.cents(438)
        val initialCredit = -commander.MIN_BALANCE - DollarAmount.cents(1)
        val (accountPre, _) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          val account = paidAccountRepo.save(
            paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get)
              .copy(credit = initialCredit).withPaymentDueAt(Some(currentDateTime)).withPaymentStatus(PaymentStatus.Ok)
          )
          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(stripeClient.getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))

          (account, plan)
        }
        accountPre.owed should beGreaterThan(commander.MIN_BALANCE)
        accountPre.paymentStatus === PaymentStatus.Ok
        Await.result(commander.processAccount(accountPre), Duration.Inf) should throwA[com.stripe.exception.APIConnectionException]

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.paymentStatus === accountPre.paymentStatus
          updatedAccount.credit === accountPre.credit
          updatedAccount.paymentDueAt === accountPre.paymentDueAt
        }
      }
    }

    "refund a valid charge" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PaymentProcessingCommander]
        val price = DollarAmount.cents(438)
        val initialCredit = -commander.MIN_BALANCE - DollarAmount.cents(1)
        val (accountPre, _, léo) = db.readWrite { implicit session =>
          val user = UserFactory.user().saved
          val léo = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val plan = PaidPlanFactory.paidPlan().withPricePerCyclePerUser(price).withBillingCycle(BillingCycle(1)).saved
          val account = paidAccountRepo.save(
            paidAccountRepo.getByOrgId(org.id.get).withNewPlan(plan.id.get).withPaymentDueAt(Some(currentDateTime)).withPaymentStatus(PaymentStatus.Ok).copy(credit = initialCredit)
          )
          inject[PaymentMethodRepo].save(PaymentMethod(
            accountId = account.id.get,
            default = true,
            stripeToken = Await.result(inject[StripeClient].getPermanentToken("fake_temporary_token", ""), Duration.Inf)
          ))
          (account, plan, léo)
        }
        accountPre.owed should beGreaterThan(commander.MIN_BALANCE)
        accountPre.paymentStatus === PaymentStatus.Ok

        // Charge the account
        val (_, chargeEvent) = Await.result(commander.processAccount(accountPre), Duration.Inf)
        chargeEvent.action === AccountEventAction.Charge()
        chargeEvent.creditChange === accountPre.owed
        chargeEvent.paymentCharge === Some(accountPre.owed)
        chargeEvent.chargeId should beSome

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.paymentStatus === PaymentStatus.Ok
          updatedAccount.credit === DollarAmount.ZERO
          updatedAccount.paymentDueAt === None
        }

        // Now try and refund this charge

        val (_, refundEvent) = Await.result(commander.refundCharge(chargeEvent.id.get, léo.id.get), Duration.Inf)
        refundEvent.action === AccountEventAction.Refund(chargeEvent.id.get, chargeEvent.chargeId.get)
        refundEvent.creditChange === -chargeEvent.creditChange
        refundEvent.paymentCharge === chargeEvent.paymentCharge.map(-_)
        refundEvent.chargeId should beSome

        db.readOnlyMaster { implicit session =>
          val updatedAccount = inject[PaidAccountRepo].get(accountPre.id.get)
          updatedAccount.paymentStatus === PaymentStatus.Ok
          updatedAccount.credit === initialCredit
        }
      }
    }
  }
}
