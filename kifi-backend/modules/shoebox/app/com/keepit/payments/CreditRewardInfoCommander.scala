package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.OrganizationCommander
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[CreditRewardInfoCommanderImpl])
trait CreditRewardInfoCommander {
  def getRewardsByOrg(orgId: Id[Organization]): Seq[ExternalCreditReward]
  def getDescription(id: Id[CreditReward])(implicit session: RSession): DescriptionElements
}

@Singleton
class CreditRewardInfoCommanderImpl @Inject() (
  db: Database,
  orgRepo: OrganizationRepo,
  creditCodeInfoRepo: CreditCodeInfoRepo,
  creditRewardRepo: CreditRewardRepo,
  accountRepo: PaidAccountRepo,
  basicUserRepo: BasicUserRepo,
  orgCommander: OrganizationCommander,
  clock: Clock,
  orgExpRepo: OrganizationExperimentRepo,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends CreditRewardInfoCommander with Logging {

  def getRewardsByOrg(orgId: Id[Organization]): Seq[ExternalCreditReward] = db.readOnlyMaster { implicit session =>
    val creditRewards = creditRewardRepo.getByAccount(accountRepo.getByOrgId(orgId).id.get)
      .toSeq.sortBy(_.applied.nonEmpty)
    creditRewards.map { cr =>
      ExternalCreditReward(
        applied = cr.applied.map(eventId => AccountEvent.publicId(eventId))
      )
    }
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  private def getOrg(id: Id[Organization])(implicit session: RSession): BasicOrganization = orgCommander.getBasicOrganizationHelper(id).getOrElse(throw new Exception(s"Tried to build event info for dead org: $id"))
  def getDescription(id: Id[CreditReward])(implicit session: RSession): DescriptionElements = {
    val creditReward = creditRewardRepo.get(id)
    val reason = creditReward.reward match {
      case Reward(kind, _, _) if kind == RewardKind.Coupon =>
        DescriptionElements(getUser(creditReward.code.get.usedBy), "redeemed the coupon code", creditReward.code.get.code, ".")
      case Reward(kind, _, _) if kind == RewardKind.OrganizationCreation =>
        DescriptionElements("you created a team on Kifi. Thanks for being awesome! :)")
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
