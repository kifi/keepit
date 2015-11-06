package com.keepit.payments

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.payments.RewardKind.RewardChecklistKind
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsSuccess, Json }

class RewardsCheckerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "RewardsChecker" should {
    "backfill checklist rewards" in {
      "backfill rewards to an org" in {
        withDb(modules: _*) { implicit injector =>
          val Seq(org1, org2) = db.readWrite { implicit s =>
            OrganizationFactory.organizations(2).map(_.withOwner(UserFactory.user().saved)).saved
          }
          // break org1
          db.readWrite { implicit s =>
            val accountId = paidAccountRepo.getAccountId(org1.id.get)
            creditRewardRepo.getByAccount(accountId).foreach(creditRewardRepo.deactivate)
          }

          // it's definitely broken
          val expectedRewards = RewardKind.allActive.collect { case k: RewardChecklistKind => Reward(k)(k.Started)(org1.id.get) }
          db.readOnlyMaster { implicit s =>
            expectedRewards.foreach { r => creditRewardRepo.getByReward(r) must beEmpty }
            val org1RewardKinds = creditRewardRepo.getByAccount(paidAccountRepo.getAccountId(org1.id.get)).map(_.reward.kind).collect { case k: RewardChecklistKind => k }
            val org2RewardKinds = creditRewardRepo.getByAccount(paidAccountRepo.getAccountId(org2.id.get)).map(_.reward.kind).collect { case k: RewardChecklistKind => k }
            org1RewardKinds !== org2RewardKinds
          }

          // fix it
          rewardsChecker.checkAccount(org1.id.get)

          db.readOnlyMaster { implicit s =>
            expectedRewards.foreach { r => creditRewardRepo.getByReward(r) must haveSize(1) }
            val org1RewardKinds = creditRewardRepo.getByAccount(paidAccountRepo.getAccountId(org1.id.get)).map(_.reward.kind).collect { case k: RewardChecklistKind => k }
            val org2RewardKinds = creditRewardRepo.getByAccount(paidAccountRepo.getAccountId(org2.id.get)).map(_.reward.kind).collect { case k: RewardChecklistKind => k }
            org1RewardKinds === org2RewardKinds
          }
          1 === 1
        }
      }
      "avoid blocked rewards" in {
        withDb(modules: _*) { implicit injector =>
          val org = db.readWrite { implicit s =>
            OrganizationFactory.organization().withOwner(UserFactory.user().saved).saved
          }

          val expectedRewards = RewardKind.allActive.collect { case k: RewardChecklistKind => Reward(k)(k.Started)(org.id.get) }
          db.readOnlyMaster { implicit s =>
            expectedRewards.map { r => creditRewardRepo.getByReward(r) } must haveSize(expectedRewards.size)
          }

          // make it look like it's an old org with deprecated rewards
          // specifically, it has a reward for reaching 1 org member (and for some reason we have decided that they can't earn any other org member rewards because of this)
          db.readWrite { implicit s =>
            val accountId = paidAccountRepo.getAccountId(org.id.get)
            val rewardsToKill = RewardKind.OrganizationMembersReached.all.map { k => Reward(k)(k.Started)(org.id.get) }
            rewardsToKill.foreach { r => creditRewardRepo.getByReward(r).foreach(creditRewardRepo.deactivate) }

            val drk = RewardKind.OrganizationMembersReached.OrganizationMembersReached1_DUMMYKIND // Deprecated Reward Kind
            RewardKind.deprecated must contain(drk)
            creditRewardCommander.createCreditReward(CreditReward(
              accountId = accountId,
              credit = DollarAmount.dollars(42),
              applied = None,
              reward = Reward(drk)(drk.Achieved)(org.id.get),
              unrepeatable = None,
              code = None
            ), None)
          }

          // it's definitely "broken"
          val beforeFixing = db.readOnlyMaster { implicit s =>
            expectedRewards.flatMap(creditRewardRepo.getByReward).size !== expectedRewards.size
            creditRewardRepo.getByAccount(paidAccountRepo.getAccountId(org.id.get))
          }

          // "fix" it (this shouldn't do anything, because the deprecated reward blocks the missing rewards)
          rewardsChecker.checkAccount(org.id.get)

          val afterFixing = db.readOnlyMaster { implicit s =>
            expectedRewards.flatMap(creditRewardRepo.getByReward).size !== expectedRewards.size
            creditRewardRepo.getByAccount(paidAccountRepo.getAccountId(org.id.get))
          }
          beforeFixing === afterFixing
        }
      }
    }
  }
}
