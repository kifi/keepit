package com.keepit.payments

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.util.DescriptionElements
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationFactoryHelper.OrganizationPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model.{ OrganizationFactory, UserFactory }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsValue, Json }

class CreditRewardInfoCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  implicit val ctxt = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeStripeClientModule()
  )

  "CreditRewardInfoCommanderTest" should {
    "categorize and order rewards" in {
      withDb(modules: _*) { implicit injector =>
        val org = db.readWrite { implicit s =>
          OrganizationFactory.organization().withName("Team").withOwner(UserFactory.user().withName("Owner", "OwnerLN").saved).saved
        }
        val view = inject[CreditRewardInfoCommander].getRewardsByOrg(org.id.get)
        // Uncomment this block to visually inspect the rewards ordering
        /*
        (Json.toJson(view) \ "rewards").as[Seq[JsValue]].foreach { rs =>
          println(rs \ "category")
          (rs \ "items").as[Seq[JsValue]].foreach(i => println("\t" + i))
        }
        */
        view.rewards.map(_._1) === RewardCategory.all
      }
    }
    "describe earned credit rewards appropriately (for the activity log)" in {
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
          makeReward(Reward(RewardKind.Coupon)(RewardKind.Coupon.Used)(couponCode.code), Some(couponCode.code)) ->
            "You earned $42.00 because Owner redeemed the coupon code COUPON.",
          makeReward(Reward(RewardKind.OrganizationCreation)(RewardKind.OrganizationCreation.Created)(None)) ->
            "You earned $42.00 because you created a team on Kifi. Thanks for being awesome! :)",
          makeReward(Reward(RewardKind.OrganizationAvatarUploaded)(RewardKind.OrganizationAvatarUploaded.Achieved)(org.id.get)) ->
            "You earned $42.00 because you uploaded an image for your team.",
          makeReward(Reward(RewardKind.OrganizationDescriptionAdded)(RewardKind.OrganizationDescriptionAdded.Achieved)(org.id.get)) ->
            "You earned $42.00 because you added a description for your team.",
          makeReward(Reward(RewardKind.OrganizationMembersReached.OrganizationMembersReached10)(RewardKind.OrganizationMembersReached.OrganizationMembersReached10.Achieved)(org.id.get)) ->
            "You earned $42.00 because your team reached 10 total members.",
          makeReward(Reward(RewardKind.OrganizationLibrariesReached.OrganizationLibrariesReached7)(RewardKind.OrganizationLibrariesReached.OrganizationLibrariesReached7.Achieved)(org.id.get)) ->
            "You earned $42.00 because your team reached 7 total libraries.",
          makeReward(Reward(RewardKind.OrganizationGeneralLibraryKeepsReached50)(RewardKind.OrganizationGeneralLibraryKeepsReached50.Achieved)(org.id.get)) ->
            "You earned $42.00 because your team added 50 keeps into the General library.",
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
    "describe checklist rewards (for the rewards checklist)" in {
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

        val rewards = Seq(
          Reward(RewardKind.OrganizationCreation)(RewardKind.OrganizationCreation.Created)(None) ->
            ("Create a team.", "Created a team. Welcome to Kifi!"),
          Reward(RewardKind.OrganizationAvatarUploaded)(RewardKind.OrganizationAvatarUploaded.Achieved)(org.id.get) ->
            ("Add your team's logo.", "Added a team logo."),
          Reward(RewardKind.OrganizationDescriptionAdded)(RewardKind.OrganizationDescriptionAdded.Achieved)(org.id.get) ->
            ("Add a description of your team.", "Added a description of your team."),
          Reward(RewardKind.OrganizationMembersReached.OrganizationMembersReached10)(RewardKind.OrganizationMembersReached.OrganizationMembersReached10.Achieved)(org.id.get) ->
            ("Reach a total of 10 members.", "Reached a total of 10 members."),
          Reward(RewardKind.OrganizationLibrariesReached.OrganizationLibrariesReached7)(RewardKind.OrganizationLibrariesReached.OrganizationLibrariesReached7.Achieved)(org.id.get) ->
            ("Add 7 libraries within the team.", "Added 7 libraries within the team."),
          Reward(RewardKind.OrganizationGeneralLibraryKeepsReached50)(RewardKind.OrganizationGeneralLibraryKeepsReached50.Achieved)(org.id.get) ->
            ("Add 50 keeps into the General library.", "Added 50 keeps into the General library."),
          Reward(RewardKind.Coupon)(RewardKind.Coupon.Used)(couponCode.code) ->
            ("Apply the coupon code COUPON.", "Applied the coupon code COUPON."),
          Reward(RewardKind.ReferralApplied)(RewardKind.ReferralApplied.Applied)(refCode.code) ->
            ("Redeem the referral code REF.", "Redeemed the referral code REF.")
        )

        db.readOnlyMaster { implicit s =>
          rewards.foreach {
            case (input, (pre, post)) =>
              DescriptionElements.formatPlain(creditRewardInfoCommander.describeReward(input, achieved = false)) === pre
              DescriptionElements.formatPlain(creditRewardInfoCommander.describeReward(input, achieved = true)) === post
          }
        }
        1 === 1
      }
    }
  }

}
