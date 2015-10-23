package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{ OrganizationFactory, UserFactory }
import com.keepit.payments.CreditCodeFail.CreditCodeAlreadyBurnedException
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

//things to test: add_user, remvove_user, change plan free to paid, all paths through charge processing

class CreditRewardCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  "CreditRewardCommanderTest" should {
    "create new credit codes" in {
      "get or create depending on whether the org has a code" in {
        withDb(modules: _*) { implicit injector =>
          val org = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            org
          }
          val code = creditRewardCommander.getOrCreateReferralCode(org.id.get)
          db.readOnlyMaster { implicit session =>
            creditCodeInfoRepo.all.map(_.code) === Seq(code)
          }

          // calling it should be idempotent
          creditRewardCommander.getOrCreateReferralCode(org.id.get) === code
          db.readOnlyMaster { implicit session =>
            creditCodeInfoRepo.all.map(_.code) === Seq(code)
          }
        }
      }
    }
    "apply referral codes" in {
      "prevent invalid attempts at code application" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, org1, org2) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org1 = OrganizationFactory.organization().withOwner(owner).saved
            val org2 = OrganizationFactory.organization().withOwner(owner).saved
            (owner, org1, org2)
          }

          val code = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          db.readOnlyMaster { implicit session => creditCodeInfoRepo.all.map(_.code) === Seq(code) }

          val badRequestsAndTheirFailures = Seq(
            CreditCodeApplyRequest(code, owner.id.get, None) -> CreditCodeFail.NoPaidAccountException(owner.id.get, None),
            CreditCodeApplyRequest(CreditCode("garbagecode"), owner.id.get, Some(org2.id.get)) -> CreditCodeFail.CreditCodeNotFoundException(CreditCode("garbagecode")),
            CreditCodeApplyRequest(code, owner.id.get, Some(org1.id.get)) -> CreditCodeFail.CreditCodeAbuseException(CreditCodeApplyRequest(code, owner.id.get, Some(org1.id.get)))
          )
          for ((badRequest, fail) <- badRequestsAndTheirFailures) {
            creditRewardCommander.applyCreditCode(badRequest) must beFailedTry(fail)
          }
          1 === 1
        }
      }
      "apply org referral credit codes on org creation" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, org1, org2) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org1 = OrganizationFactory.organization().withOwner(owner).saved
            val org2 = OrganizationFactory.organization().withOwner(owner).saved
            (owner, org1, org2)
          }

          val code = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          db.readOnlyMaster { implicit session => creditCodeInfoRepo.all.map(_.code) === Seq(code) }

          val initialCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org2.id.get).credit }
          val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code, owner.id.get, Some(org2.id.get))).get
          val finalCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org2.id.get).credit }
          finalCredit - initialCredit === rewards.target.credit

          db.readOnlyMaster { implicit session =>
            creditRewardRepo.all === Seq(rewards.target, rewards.referrer.get)
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents must haveSize(1)
            rewardCreditEvents.head.creditChange === rewards.target.credit
          }

          paymentsChecker.checkAccount(org1.id.get) must beEmpty
          paymentsChecker.checkAccount(org2.id.get) must beEmpty
        }
      }
    }

    "apply coupon codes" in {
      "give an org credit if they apply a coupon code" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, org, coupon) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val coupon = creditCodeInfoRepo.create(CreditCodeInfo(
              kind = CreditCodeKind.Coupon,
              code = CreditCode(RandomStringUtils.randomAlphanumeric(20)),
              status = CreditCodeStatus.Open,
              referrer = None)).get
            (owner, org, coupon)
          }

          val initialCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org.id.get).credit }
          val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(coupon.code, owner.id.get, Some(org.id.get))).get
          val finalCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org.id.get).credit }
          finalCredit - initialCredit === rewards.target.credit

          db.readOnlyMaster { implicit session =>
            creditRewardRepo.all === Seq(rewards.target)
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents must haveSize(1)
            rewardCreditEvents.head.creditChange === rewards.target.credit
          }

          paymentsChecker.checkAccount(org.id.get) must beEmpty
        }
      }
      "only let a one-time-use coupon be used once" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, org1, org2, coupon) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org1 = OrganizationFactory.organization().withOwner(owner).saved
            val org2 = OrganizationFactory.organization().withOwner(owner).saved
            val coupon = creditCodeInfoRepo.create(CreditCodeInfo(
              kind = CreditCodeKind.Coupon,
              code = CreditCode(RandomStringUtils.randomAlphanumeric(20)),
              status = CreditCodeStatus.Open,
              referrer = None)).get
            (owner, org1, org2, coupon)
          }

          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(coupon.code, owner.id.get, Some(org1.id.get))) must beSuccessfulTry

          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(coupon.code, owner.id.get, Some(org1.id.get))) must beFailedTry(CreditCodeAlreadyBurnedException(coupon.code))
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(coupon.code, owner.id.get, Some(org2.id.get))) must beFailedTry(CreditCodeAlreadyBurnedException(coupon.code))

          db.readOnlyMaster { implicit session =>
            creditRewardRepo.count === 1
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents must haveSize(1)
          }

          paymentsChecker.checkAccount(org1.id.get) must beEmpty
          paymentsChecker.checkAccount(org2.id.get) must beEmpty
        }
      }
    }
  }

}
