package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{ OrganizationFactory, UserFactory }
import com.keepit.test.ShoeboxTestInjector
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
    "apply credit codes" in {
      "apply org referral credit codes" in {
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
            CreditCodeApplyRequest(code, owner.id.get, Some(org1.id.get)) -> CreditCodeFail.CreditCodeNotApplicable(CreditCodeApplyRequest(code, owner.id.get, Some(org1.id.get)))
          )
          for ((badRequest, fail) <- badRequestsAndTheirFailures) {
            creditRewardCommander.applyCreditCode(badRequest) must beFailedTry(fail)
          }

          val response = creditRewardCommander.applyCreditCode(CreditCodeApplyRequest(code, owner.id.get, Some(org2.id.get))).get
          db.readOnlyMaster { implicit session =>
            creditRewardRepo.all === Seq(response.target, response.referrer.get)
          }
        }
      }
    }
  }

}
