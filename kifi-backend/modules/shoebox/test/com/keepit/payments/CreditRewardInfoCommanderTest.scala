package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.{ OrganizationFactory, UserFactory }
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class CreditRewardInfoCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  implicit val ctxt = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  "CreditRewardInfoCommanderTest" should {
    "describe credit rewards appropriately" in {
      withDb(modules: _*) { implicit injector =>
        val (org, account, owner) = db.readWrite { implicit s =>
          val owner = UserFactory.user().withName("Owner", "OwnerLN").saved
          val org = OrganizationFactory.organization().withName("Team").withOwner(owner).saved
          val account = paidAccountRepo.getByOrgId(org.id.get)
          (org, account, owner)
        }

        val Seq(couponCode, promoCode, refCode) = db.readWrite { implicit s =>
          Seq(
            creditCodeInfoRepo.create(CreditCodeInfo(kind = CreditCodeKind.Coupon, code = CreditCode.normalize("coupon"), credit = DollarAmount.dollars(42), status = CreditCodeStatus.Open, referrer = None)).get,
            creditCodeInfoRepo.create(CreditCodeInfo(kind = CreditCodeKind.Promotion, code = CreditCode.normalize("promo"), credit = DollarAmount.dollars(42), status = CreditCodeStatus.Open, referrer = None)).get,
            creditCodeInfoRepo.create(CreditCodeInfo(kind = CreditCodeKind.OrganizationReferral, code = CreditCode.normalize("ref"), credit = DollarAmount.dollars(42), status = CreditCodeStatus.Open, referrer = Some(CreditCodeReferrer(org.ownerId, org.id, DollarAmount.dollars(42))))).get
          )
        }
        def makeReward(r: Reward, code: Option[CreditCode] = None) = CreditReward(
          accountId = account.id.get,
          credit = DollarAmount.dollars(42),
          applied = if (r.status == r.kind.applicable) Some(Id(1)) else None, // throw in a fake account event
          reward = r,
          unrepeatable = Some(UnrepeatableRewardKey.WasCreated(org.id.get)),
          code = code.map(c => UsedCreditCode(c, singleUse = false, owner.id.get))
        )

        val rewards = Seq(
          makeReward(Reward(RewardKind.Coupon)(RewardKind.Coupon.Used)(None), Some(couponCode.code)) ->
            "You earned $42.00 because Owner redeemed the coupon code COUPON.",
          makeReward(Reward(RewardKind.OrganizationCreation)(RewardKind.OrganizationCreation.Created)(None)) ->
            "You earned $42.00 because you created a team on Kifi. Thanks for being awesome! :)",
          makeReward(Reward(RewardKind.OrganizationAvatarUploaded)(RewardKind.OrganizationAvatarUploaded.Started)(org.id.get)) ->
            "You will get $42.00 when you upload an image for your team.",
          makeReward(Reward(RewardKind.OrganizationAvatarUploaded)(RewardKind.OrganizationAvatarUploaded.Achieved)(org.id.get)) ->
            "You earned $42.00 because you uploaded an image for your team.",
          makeReward(Reward(RewardKind.OrganizationDescriptionAdded)(RewardKind.OrganizationDescriptionAdded.Started)(org.id.get)) ->
            "You will get $42.00 when you tell us about your team.",
          makeReward(Reward(RewardKind.OrganizationDescriptionAdded)(RewardKind.OrganizationDescriptionAdded.Achieved)(org.id.get)) ->
            "You earned $42.00 because you told us about your team.",
          makeReward(Reward(RewardKind.OrganizationMembersReached10)(RewardKind.OrganizationMembersReached10.Started)(org.id.get)) ->
            "You will get $42.00 when your team reaches 10 total members.",
          makeReward(Reward(RewardKind.OrganizationMembersReached10)(RewardKind.OrganizationMembersReached10.Achieved)(org.id.get)) ->
            "You earned $42.00 because your team reached 10 total members.",
          makeReward(Reward(RewardKind.OrganizationLibrariesReached10)(RewardKind.OrganizationLibrariesReached10.Started)(org.id.get)) ->
            "You will get $42.00 when your team reaches 10 total libraries.",
          makeReward(Reward(RewardKind.OrganizationLibrariesReached10)(RewardKind.OrganizationLibrariesReached10.Achieved)(org.id.get)) ->
            "You earned $42.00 because your team reached 10 total libraries.",
          makeReward(Reward(RewardKind.OrganizationGeneralLibraryKeepsReached50)(RewardKind.OrganizationGeneralLibraryKeepsReached50.Started)(org.id.get)) ->
            "You will get $42.00 when your team adds 50 keeps into the General library.",
          makeReward(Reward(RewardKind.OrganizationGeneralLibraryKeepsReached50)(RewardKind.OrganizationGeneralLibraryKeepsReached50.Achieved)(org.id.get)) ->
            "You earned $42.00 because your team added 50 keeps into the General library.",
          makeReward(Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Created)(org.id.get)) ->
            "You will get $42.00 when Team upgrades to a pro account.",
          makeReward(Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Upgraded)(org.id.get)) ->
            "You earned $42.00 because you referred Team. Thank you!",
          makeReward(Reward(RewardKind.ReferralApplied)(RewardKind.ReferralApplied.Applied)(promoCode.code), Some(promoCode.code)) ->
            "You earned $42.00 because Owner applied the code PROMO.",
          makeReward(Reward(RewardKind.ReferralApplied)(RewardKind.ReferralApplied.Applied)(refCode.code), Some(refCode.code)) ->
            "You earned $42.00 because Owner applied the code REF from Team."
        )

        db.readOnlyMaster { implicit s =>
          rewards.foreach {
            case (input, output) => DescriptionElements.formatPlain(inject[CreditRewardInfoCommander].getDescription(input)) === output
          }
        }
        1 === 1
      }
    }
  }

}
