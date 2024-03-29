package com.keepit.payments

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.{ FakeShoeboxStoreModule, ImagePath }
import com.keepit.common.util.DollarAmount
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.payments.RewardKind.RewardChecklistKind
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

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
    "trigger rewards that may have been missed" in {
      "retroactively" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, orgId, orgGeneralLib) = db.readWrite { implicit s =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val orgGeneralLib = libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(org.id.get), LibraryKind.SYSTEM_ORG_GENERAL).head
            (owner, org.id.get, orgGeneralLib)
          }

          val kindsAndFunctions = Seq[(RewardChecklistKind, RWSession => Unit)](
            RewardKind.OrganizationAvatarUploaded -> { implicit s =>
              inject[OrganizationAvatarRepo].save(OrganizationAvatar(organizationId = orgId, width = 1, height = 1, format = ImageFormat.PNG,
                kind = ProcessImageOperation.CropScale, imagePath = ImagePath("foo"), source = ImageSource.Unknown, sourceFileHash = ImageHash("foo"), sourceImageURL = None))
            },

            RewardKind.OrganizationDescriptionAdded -> { implicit s =>
              orgRepo.save(orgRepo.get(orgId).withDescription(Some("dummy")))
            },

            RewardKind.OrganizationGeneralLibraryKeepsReached50 -> { implicit s =>
              KeepFactory.keeps(50).map(_.withUser(owner).withLibrary(orgGeneralLib)).saved
            },

            RewardKind.OrganizationLibrariesReached.OrganizationLibrariesReached7 -> { implicit s =>
              LibraryFactory.libraries(7).map(_.withOwner(owner).withOrganizationIdOpt(Some(orgId))).saved
            },

            RewardKind.OrganizationMembersReached.OrganizationMembersReached5 -> { implicit s =>
              UserFactory.users(4).saved.foreach { u => orgMembershipRepo.save(OrganizationMembership(organizationId = orgId, userId = u.id.get, role = OrganizationRole.MEMBER)) }
            }
          )

          for ((k, fn) <- kindsAndFunctions) {
            db.readWrite { implicit s =>
              // Check that the reward hasn't been achieved
              creditRewardRepo.getByReward(Reward(k)(k.Started)(orgId)) must haveSize(1)
              creditRewardRepo.getByReward(Reward(k)(k.Achieved)(orgId)) must beEmpty
              // Do the function
              fn(s)
              // Make sure it still hasn't been achieved
              creditRewardRepo.getByReward(Reward(k)(k.Started)(orgId)) must haveSize(1)
              creditRewardRepo.getByReward(Reward(k)(k.Achieved)(orgId)) must beEmpty
            }
            // Run the checker
            rewardsChecker.checkAccount(orgId)
            // It should have been triggered
            db.readOnlyMaster { implicit s =>
              creditRewardRepo.getByReward(Reward(k)(k.Started)(orgId)) must beEmpty
              creditRewardRepo.getByReward(Reward(k)(k.Achieved)(orgId)) must haveSize(1)
            }
          }
          1 === 1
        }
      }
    }
  }
}
