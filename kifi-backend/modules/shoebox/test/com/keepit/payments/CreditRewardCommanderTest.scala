package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.mail.{ SystemEmailAddress, EmailAddress, ElectronicMailRepo }
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{ OrganizationFactory, UserFactory }
import com.keepit.payments.CreditRewardFail.{ UnrepeatableRewardKeyCollisionException, CreditCodeAlreadyBurnedException }
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

import scala.util.Random

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
            CreditCodeApplyRequest(CreditCode("GARBAGE-CODE"), org2.ownerId, Some(org2.id.get)) -> CreditRewardFail.CreditCodeNotFoundException(CreditCode("GARBAGE-CODE")),
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
          val ownerEmail = "roger_rabbit@csi.gov"
          val (org1, org2, owner1) = db.readWrite { implicit session =>
            val owner1 = UserFactory.user().withEmailAddress(ownerEmail).saved
            val org1 = OrganizationFactory.organization().withOwner(owner1).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            (org1, org2, owner1)
          }

          val code = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          db.readOnlyMaster { implicit session => creditCodeInfoRepo.all.map(_.code) === Seq(code) }

          val initialCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org2.id.get).credit }
          val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code, org2.ownerId, Some(org2.id.get))).get
          val finalCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org2.id.get).credit }
          finalCredit - initialCredit === rewards.target.credit

          db.readOnlyMaster { implicit session =>
            creditRewardRepo.all must containAllOf(Seq(rewards.target, rewards.referrer.get))
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents.size must beGreaterThanOrEqualTo(1)
            rewardCreditEvents.map(_.creditChange) must contain(rewards.target.credit)
            inject[ElectronicMailRepo].all().count(email =>
              email.from === SystemEmailAddress.NOTIFICATIONS &&
                email.subject == s"Your team's referral code was used by ${org2.name} on Kifi") must equalTo(1)
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
          val expectedFailure = UnrepeatableRewardKeyCollisionException(UnrepeatableRewardKey.WasReferred(org3.id.get))
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code1, org3.ownerId, Some(org3.id.get))) must beFailedTry(expectedFailure)
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code2, org3.ownerId, Some(org3.id.get))) must beFailedTry(expectedFailure)

          paymentsChecker.checkAccount(org1.id.get) must beEmpty
          paymentsChecker.checkAccount(org2.id.get) must beEmpty
          paymentsChecker.checkAccount(org3.id.get) must beEmpty
        }
      }
      "apply rewards to the referrer once the referred org upgrades" in {
        withDb(modules: _*) { implicit injector =>
          val owner1Email = "roger_rabbit@csi.gov"
          val (org1, org2, owner1) = db.readWrite { implicit session =>
            val owner1 = UserFactory.user().withEmailAddress(owner1Email).saved
            val org1 = OrganizationFactory.organization().withOwner(owner1).saved
            val org2 = OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
            (org1, org2, owner1)
          }

          // org1 refers org2
          val code = creditRewardCommander.getOrCreateReferralCode(org1.id.get)
          val rewards = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code, org2.ownerId, Some(org2.id.get))).get

          // org1 has a Reward now, but it has not been applied because org2 has not upgraded
          val expectedReward = Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Created)(org2.id.get)
          rewards.referrer.get.reward === expectedReward

          db.readOnlyMaster { implicit session =>
            val cr = creditRewardRepo.getByReward(expectedReward)
            cr must haveSize(1)
            cr.head.applied must beNone
          }

          // when org2 upgrades, org1 gets some credit
          val initialCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org1.id.get).credit }
          val upgradeRewards = db.readWrite { implicit session => creditRewardCommander.registerUpgradedAccount(org2.id.get) }
          val finalCredit = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(org1.id.get).credit }
          finalCredit - initialCredit === rewards.referrer.get.credit

          upgradeRewards must haveSize(1)
          upgradeRewards.head.applied must beSome

          db.readOnlyMaster { implicit session =>
            inject[ElectronicMailRepo].all().count(email =>
              email.from == SystemEmailAddress.NOTIFICATIONS &&
                email.to.contains(EmailAddress(owner1Email)) &&
                email.subject == s"You earned a $$100 credit for ${org1.name} on Kifi") must equalTo(1)
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
            creditRewardRepo.all must containAllOf(Seq(rewards.target))
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents.size must beGreaterThanOrEqualTo(1)
            rewardCreditEvents.map(_.creditChange) must contain(rewards.target.credit)
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

          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(coupon.code, owner.id.get, Some(org1.id.get))) must beFailedTry
          creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(coupon.code, owner.id.get, Some(org2.id.get))) must beFailedTry

          db.readOnlyMaster { implicit session =>
            creditRewardRepo.count must beGreaterThanOrEqualTo(1)
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents.length must beGreaterThanOrEqualTo(1)
            rewardCreditEvents.map(_.creditChange) must contain(coupon.credit)
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
            creditRewardRepo.count must beGreaterThanOrEqualTo(2)
            val rewardCreditEvents = accountEventRepo.all.filter(_.action.eventType == AccountEventKind.RewardCredit)
            rewardCreditEvents.size must beGreaterThanOrEqualTo(2)
            rewardCreditEvents.map(e => paidAccountRepo.get(e.accountId).orgId) must containAllOf(Seq(org1.id.get, org2.id.get))
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
            val expectedFailure = UnrepeatableRewardKeyCollisionException(UnrepeatableRewardKey.WasReferred(org.id.get))
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
  }

}
