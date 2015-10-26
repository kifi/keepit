package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{ OrganizationFactory, UserFactory }
import com.keepit.payments.CreditRewardFail.{ UnrepeatableRewardKeyCollisionException, CreditCodeAlreadyBurnedException }
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

import scala.util.Random

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
          val (org1, org2) = db.readWrite { implicit session =>
            val org1 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            (org1, org2)
          }

          val code = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          db.readOnlyMaster { implicit session => creditCodeInfoRepo.all.map(_.code) === Seq(code) }

          val badRequestsAndTheirFailures = Seq(
            CreditCodeApplyRequest(code, org1.ownerId, None) -> CreditRewardFail.NoPaidAccountException(org1.ownerId, None),
            CreditCodeApplyRequest(CreditCode("garbagecode"), org2.ownerId, Some(org2.id.get)) -> CreditRewardFail.CreditCodeNotFoundException(CreditCode("garbagecode")),
            CreditCodeApplyRequest(code, org1.ownerId, Some(org1.id.get)) -> CreditRewardFail.CreditCodeAbuseException(CreditCodeApplyRequest(code, org1.ownerId, Some(org1.id.get)))
          )
          for ((badRequest, fail) <- badRequestsAndTheirFailures) {
            creditRewardCommander.applyCreditCode(badRequest) must beFailedTry(fail)
          }
          1 === 1
        }
      }
      "apply org referral credit codes on org creation" in {
        withDb(modules: _*) { implicit injector =>
          val (org1, org2) = db.readWrite { implicit session =>
            val org1 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            (org1, org2)
          }

          val code = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          db.readOnlyMaster { implicit session => creditCodeInfoRepo.all.map(_.code) === Seq(code) }

          val initialCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org2.id.get).credit }
          val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code, org2.ownerId, Some(org2.id.get))).get
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
      "prevent polygamous referrals" in {
        withDb(modules: _*) { implicit injector =>
          val (org1, org2, org3) = db.readWrite { implicit session =>
            val org1 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org3 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            (org1, org2, org3)
          }

          val code1 = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          val code2 = creditRewardCommander.getOrCreateReferralCode(org2.id.get)

          // org1 and org2 both invite org3
          // org3 accepts from org1
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code1, org3.ownerId, Some(org3.id.get))) must beSuccessfulTry
          // so they can't accept from anyone else (or double-accept from org1)
          val expectedFailure = UnrepeatableRewardKeyCollisionException(UnrepeatableRewardKey.NewOrganization(org3.id.get))
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code1, org3.ownerId, Some(org3.id.get))) must beFailedTry(expectedFailure)
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code2, org3.ownerId, Some(org3.id.get))) must beFailedTry(expectedFailure)

          paymentsChecker.checkAccount(org1.id.get) must beEmpty
          paymentsChecker.checkAccount(org2.id.get) must beEmpty
          paymentsChecker.checkAccount(org3.id.get) must beEmpty
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
              credit = DollarAmount.dollars(50 + Random.nextInt(50)),
              code = CreditCode.normalize(RandomStringUtils.randomAlphanumeric(20)),
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
              credit = DollarAmount.dollars(50 + Random.nextInt(50)),
              code = CreditCode.normalize(RandomStringUtils.randomAlphanumeric(20)),
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
    "apply promo codes" in {
      "let a promo code be used by multiple orgs" in {
        withDb(modules: _*) { implicit injector =>
          val (org1, org2, promo) = db.readWrite { implicit session =>
            val org1 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val promo = creditCodeInfoRepo.create(CreditCodeInfo(
              kind = CreditCodeKind.Promotion,
              credit = DollarAmount.dollars(42),
              code = CreditCode.normalize("kifirocks-2015"),
              status = CreditCodeStatus.Open,
              referrer = None)).get
            (org1, org2, promo)
          }

          for (org <- Seq(org1, org2)) {
            val initialCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org.id.get).credit }
            val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(promo.code, org.ownerId, Some(org.id.get))).get
            rewards.referrer must beNone
            val finalCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org.id.get).credit }
            finalCredit - initialCredit === rewards.target.credit
          }

          db.readOnlyMaster { implicit session =>
            creditRewardRepo.count === 2
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents must haveSize(2)
            rewardCreditEvents.map(e => paidAccountRepo.get(e.accountId).orgId) === Seq(org1.id.get, org2.id.get)
          }

          paymentsChecker.checkAccount(org1.id.get) must beEmpty
          paymentsChecker.checkAccount(org2.id.get) must beEmpty
        }
      }
      "do not let orgs mix referral codes or multiple promo codes" in {
        withDb(modules: _*) { implicit injector =>
          val (org1, org2, org3, promos) = db.readWrite { implicit session =>
            val org1 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val org3 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            val promos = (1 to 3).toList.map { i =>
              creditCodeInfoRepo.create(CreditCodeInfo(
                kind = CreditCodeKind.Promotion,
                credit = DollarAmount.dollars(42),
                code = CreditCode.normalize(s"kifirocks-2015-$i"),
                status = CreditCodeStatus.Open,
                referrer = None)).get
            }
            (org1, org2, org3, promos)
          }

          val referralCode = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          // org2 accepts a referral
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(referralCode, org2.ownerId, Some(org2.id.get))) must beSuccessfulTry
          // org3 uses a promo code
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(promos.head.code, org3.ownerId, Some(org3.id.get))) must beSuccessfulTry

          for (org <- Seq(org2, org3)) {
            val expectedFailure = UnrepeatableRewardKeyCollisionException(UnrepeatableRewardKey.NewOrganization(org.id.get))
            creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(referralCode, org.ownerId, Some(org.id.get))) must beFailedTry(expectedFailure)
            for (promo <- promos) {
              creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(promo.code, org.ownerId, Some(org.id.get))) must beFailedTry(expectedFailure)
            }
          }

          paymentsChecker.checkAccount(org1.id.get) must beEmpty
          paymentsChecker.checkAccount(org2.id.get) must beEmpty
          paymentsChecker.checkAccount(org3.id.get) must beEmpty
        }
      }
    }

    "let me do awesome things" in {
      withDb(modules: _*) { implicit injector =>
        val (org1, org2) = db.readWrite { implicit session =>
          (
            OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved,
            OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
          )
        }

        val referralCode = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
        val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(referralCode, org2.ownerId, Some(org2.id.get))).get

        println(rewards.target.reward.dump(creditRewardCommander))
        println(rewards.referrer.get.reward.dump(creditRewardCommander))
        1 === 1
      }
    }
  }

}
