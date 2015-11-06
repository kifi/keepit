package com.keepit.payments

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.payments.RewardKind.RewardChecklistKind

@Singleton
class RewardsChecker @Inject() (
  db: Database,
  clock: Clock,
  creditRewardRepo: CreditRewardRepo,
  creditRewardCommander: CreditRewardCommander,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  userExperimentRepo: UserExperimentRepo,
  // For double-checking checklist rewards
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  orgRepo: OrganizationRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgAvatarRepo: OrganizationAvatarRepo,
  airbrake: AirbrakeNotifier)
    extends Logging {

  private def rewardsPreventedBy(kind: RewardChecklistKind): Set[RewardChecklistKind] = kind match {
    case RewardKind.OrganizationMembersReached.OrganizationMembersReached1_DUMMYKIND =>
      RewardKind.OrganizationMembersReached.all.map(x => x: RewardChecklistKind)
    case _ => Set.empty[RewardChecklistKind]
  }
  private def backfillChecklistRewards(orgId: Id[Organization]): Unit = db.readWrite { implicit s =>
    val accountId = paidAccountRepo.getAccountId(orgId)
    val currentRewards: Set[RewardChecklistKind] = creditRewardRepo.getByAccount(accountId).map(_.reward.kind).collect { case k: RewardChecklistKind => k }
    val blockedRewards = currentRewards.flatMap(rewardsPreventedBy)

    RewardKind.allActive.collect {
      case k: RewardChecklistKind if !currentRewards.contains(k) && !blockedRewards.contains(k) =>
        creditRewardCommander.initializeChecklistReward(orgId, k)
    }
  }

  private def sendValidRewardTriggers(orgId: Id[Organization]): Unit = db.readWrite { implicit s =>
    val rewardTriggers: Seq[RewardTrigger] = Seq(
      Some(RewardTrigger.OrganizationAvatarUploaded(orgId)).filter { _ => orgAvatarRepo.getByOrgId(orgId).nonEmpty },
      Some(orgRepo.get(orgId)).filter(_.description.exists(_.nonEmpty)).map { org => RewardTrigger.OrganizationDescriptionAdded(orgId, org) },
      libRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(orgId), LibraryKind.SYSTEM_ORG_GENERAL).headOption.map { orgGeneralLib =>
        RewardTrigger.OrganizationKeepAddedToGeneralLibrary(orgId, ktlRepo.getCountByLibraryId(orgGeneralLib.id.get))
      },
      Some(RewardTrigger.OrganizationAddedLibrary(orgId, libRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId)).size)),
      Some(RewardTrigger.OrganizationMemberAdded(orgId, orgMembershipRepo.countByOrgId(orgId))),
      Some(RewardTrigger.OrganizationUpgraded(orgId, paidPlanRepo.get(paidAccountRepo.getByOrgId(orgId).planId)))
    ).flatten

    rewardTriggers.foreach { creditRewardCommander.registerRewardTrigger }
  }

  def checkAccount(orgId: Id[Organization]): Unit = {
    backfillChecklistRewards(orgId)
    sendValidRewardTriggers(orgId)
  }

  def checkAccounts(modulus: Int = 7): Unit = {
    // we are going to process ~1/modulus of the orgs, based on the date
    val partition = clock.now.getDayOfYear % modulus
    // val orgIds = db.readOnlyReplica { implicit session => paidAccountRepo.getIdSubsetByModulus(modulus, partition) }

    // Until we're sure this won't give people way too much credit, only run it on orgs owned by admins
    val orgIds = db.readOnlyReplica { implicit session =>
      val adminIds = userExperimentRepo.getUserIdsByExperiment(UserExperimentType.ADMIN)
      adminIds.toSet.flatMap { uid: Id[User] => orgRepo.getAllByOwnerId(uid).map(_.id.get) }
    }
    orgIds.foreach(checkAccount)
  }

}
