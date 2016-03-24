package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.commanders.{ OrganizationInfoCommander, PathCommander }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.{ Hover, LinkElement, DescriptionElements }
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
  libraryRepo: LibraryRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  basicOrganizationGen: BasicOrganizationGen,
  pathCommander: PathCommander,
  implicit val imageConfig: S3ImageConfig,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends CreditRewardInfoCommander with Logging {

  private def subpriority(kind: RewardKind) = kind match {
    case RewardKind.OrganizationCreation => 100
    case RewardKind.OrganizationAvatarUploaded => 5
    case RewardKind.OrganizationDescriptionAdded => 4

    case k: RewardKind.OrganizationMembersReached => -k.threshold

    case k: RewardKind.OrganizationLibrariesReached => -k.threshold
    case RewardKind.OrganizationGeneralLibraryKeepsReached50 => -1000

    case RewardKind.OrganizationReferral => 0

    case RewardKind.ReferralApplied => 100
    case RewardKind.Coupon => 0
  }
  def getRewardsByOrg(orgId: Id[Organization]): CreditRewardsView = db.readOnlyMaster { implicit session =>
    val rewards = creditRewardRepo.getByAccount(accountRepo.getByOrgId(orgId).id.get).toSeq
    val categorizedRewards = RewardCategory.all.map { category =>
      category -> rewards.filter(r => RewardCategory.forKind(r.reward.kind) == category).sortBy(cr => (cr.applied.isEmpty, -subpriority(cr.reward.kind)))
    }
    val externalCategorizedRewards = categorizedRewards.map {
      case (category, crs) => category -> crs.map { cr =>
        ExternalCreditReward(
          description = describeReward(cr.reward, achieved = cr.applied.isDefined),
          credit = cr.credit,
          applied = cr.applied.map(eventId => AccountEvent.publicId(eventId))
        )
      }
    }
    CreditRewardsView(externalCategorizedRewards)
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  private def getOrg(id: Id[Organization])(implicit session: RSession): Option[BasicOrganization] = basicOrganizationGen.getBasicOrganizationHelper(id)
  def getDescription(creditReward: CreditReward)(implicit session: RSession): DescriptionElements = {
    require(creditReward.applied.isDefined)
    val reason = creditReward.reward match {
      case Reward(kind: RewardKind.OrganizationLibrariesReached, _, _) => DescriptionElements(s"your team reached ${kind.threshold} total libraries.")
      case Reward(kind: RewardKind.OrganizationMembersReached, _, _) => DescriptionElements(s"your team reached ${kind.threshold} total members.")
      case Reward(kind, _, _) if kind == RewardKind.Coupon =>
        DescriptionElements(getUser(creditReward.code.get.usedBy), "redeemed the coupon code", creditReward.code.get.code.value, ".")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationAvatarUploaded => DescriptionElements("you uploaded an image for your team.")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationCreation => DescriptionElements("you created a team on Kifi. Thanks for being awesome! :)")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationDescriptionAdded => DescriptionElements("you added a description for your team.")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationGeneralLibraryKeepsReached50 => DescriptionElements("your team added 50 keeps into the General library.")
      case Reward(kind, _, referredOrgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationReferral =>
        DescriptionElements("you referred", getOrg(referredOrgId).map(DescriptionElements.fromBasicOrg).getOrElse("a team"), ". Thank you!")
      case Reward(kind, _, _) if kind == RewardKind.ReferralApplied =>
        val referrerOpt = for {
          codeInfo <- creditCodeInfoRepo.getByCode(creditReward.code.get.code)
          referrer <- codeInfo.referrer
          referrerOrg <- referrer.organizationId
        } yield referrerOrg
        DescriptionElements(getUser(creditReward.code.get.usedBy), "applied the code", creditReward.code.get.code.value, referrerOpt.map(r => DescriptionElements("from", getOrg(r))), ".")
    }
    DescriptionElements("You earned", creditReward.credit, "because", reason)
  }

  def describeReward(reward: Reward, achieved: Boolean)(implicit session: RSession): DescriptionElements = {
    import DescriptionElements._
    reward match {
      case Reward(kind: RewardKind.OrganizationLibrariesReached, _, orgId: Id[Organization] @unchecked) =>
        val hover = Hover("Your team currently has", libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId)).size, "libraries")
        val librariesPage = LinkElement(pathCommander.orgLibrariesPageById(orgId).absolute)
        if (achieved) DescriptionElements("Added", s"${kind.threshold} libraries" --> hover --> librariesPage, "within the team.")
        else DescriptionElements("Add", s"${kind.threshold} libraries" --> hover --> librariesPage, "within the team.")
      case Reward(kind: RewardKind.OrganizationMembersReached, _, orgId: Id[Organization] @unchecked) =>
        val hover = Hover("Your team currently has", orgMembershipRepo.countByOrgId(orgId), "members")
        val membersPage = LinkElement(pathCommander.orgMembersPageById(orgId).absolute)
        if (achieved) DescriptionElements("Reached a total of", s"${kind.threshold} members" --> membersPage --> hover, ".")
        else DescriptionElements("Reach a total of", s"${kind.threshold} members" --> membersPage --> hover, ".")
      case Reward(kind, _, orgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationAvatarUploaded =>
        val orgAvatarPage = LinkElement(pathCommander.orgPageById(orgId).absolute)
        if (achieved) DescriptionElements("Added a", "team logo" --> orgAvatarPage, ".")
        else DescriptionElements("Add your team's", "logo" --> orgAvatarPage, ".")
      case Reward(kind, _, orgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationDescriptionAdded =>
        val orgDescriptionPage = LinkElement(pathCommander.orgPageById(orgId).absolute)
        if (achieved) DescriptionElements("Added a description" --> orgDescriptionPage, "of your team.")
        else DescriptionElements("Add a description" --> orgDescriptionPage, "of your team.")
      case Reward(kind, _, orgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationGeneralLibraryKeepsReached50 =>
        val orgGeneralLibrary = libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(orgId), LibraryKind.SYSTEM_ORG_GENERAL).head
        val orgGeneralLibraryPage = LinkElement(pathCommander.pathForLibrary(orgGeneralLibrary).absolute)
        if (achieved) DescriptionElements("Added 50 keeps into the", "General library" --> orgGeneralLibraryPage, ".")
        else DescriptionElements("Add", "50 keeps into the", "General library" --> orgGeneralLibraryPage, ".")
      case Reward(kind, _, referredOrgId: Id[Organization] @unchecked) if kind == RewardKind.OrganizationReferral =>
        if (achieved) DescriptionElements("Earned credit because", getOrg(referredOrgId), "upgraded their team.")
        else DescriptionElements("Earn credit when", getOrg(referredOrgId), "upgrades their team.")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationCreation =>
        if (achieved) DescriptionElements("Created a team. Welcome to Kifi!")
        else DescriptionElements("Create a team.")
      case Reward(kind, _, code: CreditCode) if kind == RewardKind.Coupon =>
        if (achieved) DescriptionElements("Applied the coupon code", code.value, ".")
        else DescriptionElements("Apply the coupon code", code.value, ".")
      case Reward(kind, _, code: CreditCode) if kind == RewardKind.ReferralApplied =>
        val orgDescription = for {
          codeInfo <- creditCodeInfoRepo.getByCode(code)
          referrer <- codeInfo.referrer
          org <- referrer.organizationId
        } yield getOrg(org)
        if (achieved) DescriptionElements("Redeemed the referral code", code.value --> Hover("from", orgDescription), ".")
        else DescriptionElements("Redeem the referral code", code.value --> Hover("from", orgDescription), ".")
    }
  }
}
