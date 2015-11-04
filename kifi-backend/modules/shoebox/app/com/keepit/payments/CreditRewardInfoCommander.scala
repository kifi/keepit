package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.OrganizationCommander
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.social.BasicUser

@ImplementedBy(classOf[CreditRewardInfoCommanderImpl])
trait CreditRewardInfoCommander {
  def getRewardsByOrg(orgId: Id[Organization]): CreditRewardsView
  def getDescription(creditReward: CreditReward)(implicit session: RSession): DescriptionElements
}

@Singleton
class CreditRewardInfoCommanderImpl @Inject() (
  db: Database,
  creditCodeInfoRepo: CreditCodeInfoRepo,
  creditRewardRepo: CreditRewardRepo,
  accountRepo: PaidAccountRepo,
  basicUserRepo: BasicUserRepo,
  orgCommander: OrganizationCommander,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends CreditRewardInfoCommander with Logging {

  def getRewardsByOrg(orgId: Id[Organization]): CreditRewardsView = db.readOnlyMaster { implicit session =>
    val rewards = creditRewardRepo.getByAccount(accountRepo.getByOrgId(orgId).id.get).toSeq
    val categorizedRewards = RewardCategory.all.map { category =>
      category -> rewards.filter(r => RewardCategory.forKind(r.reward.kind) == category).sortBy(_.applied.isDefined)
    }.toMap
    val externalCategorizedRewards = categorizedRewards.map {
      case (category, crs) => category -> crs.map { cr =>
        ExternalCreditReward(
          description = getDescription(cr),
          applied = cr.applied.map(eventId => AccountEvent.publicId(eventId))
        )
      }
    }
    CreditRewardsView(externalCategorizedRewards)
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  private def getOrg(id: Id[Organization])(implicit session: RSession): BasicOrganization = orgCommander.getBasicOrganizationHelper(id).getOrElse(throw new Exception(s"Tried to build event info for dead org: $id"))
  def getDescription(creditReward: CreditReward)(implicit session: RSession): DescriptionElements = {
    if (creditReward.applied.isDefined) describeAppliedReward(creditReward)
    else describeUnearnedReward(creditReward)
  }
  private def describeUnearnedReward(creditReward: CreditReward)(implicit session: RSession): DescriptionElements = {
    val trigger = creditReward.reward match {
      case Reward(kind, _, _) if kind == RewardKind.OrganizationDescriptionAdded =>
        DescriptionElements("you tell us about your team.")
      case Reward(kind, _, referredOrgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationReferral =>
        DescriptionElements(getOrg(referredOrgId), "upgrades to a pro account.")
    }
    DescriptionElements("You will get", creditReward.credit, "when", trigger)
  }
  private def describeAppliedReward(creditReward: CreditReward)(implicit session: RSession): DescriptionElements = {
    val reason = creditReward.reward match {
      case Reward(kind, _, _) if kind == RewardKind.Coupon =>
        DescriptionElements(getUser(creditReward.code.get.usedBy), "redeemed the coupon code", creditReward.code.get.code, ".")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationCreation =>
        DescriptionElements("you created a team on Kifi. Thanks for being awesome! :)")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationDescriptionAdded =>
        DescriptionElements("you told us about your team.")
      case Reward(kind, _, referredOrgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationReferral =>
        DescriptionElements("you referred", getOrg(referredOrgId), ". Thank you!")
      case Reward(kind, _, _) if kind == RewardKind.ReferralApplied =>
        val referrerOpt = for {
          codeInfo <- creditCodeInfoRepo.getByCode(creditReward.code.get.code)
          referrer <- codeInfo.referrer
          referrerOrg <- referrer.organizationId
        } yield referrerOrg
        DescriptionElements(getUser(creditReward.code.get.usedBy), "applied the code", creditReward.code.get.code, referrerOpt.map(r => DescriptionElements("from", getOrg(r))), ".")
    }
    DescriptionElements("You earned", creditReward.credit, "because", reason)
  }
}
