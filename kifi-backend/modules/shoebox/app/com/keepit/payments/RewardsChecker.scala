package com.keepit.payments

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.{ Organization, OrganizationRepo }
import com.keepit.payments.RewardKind.RewardChecklistKind

@Singleton
class RewardsChecker @Inject() (
  db: Database,
  clock: Clock,
  orgRepo: OrganizationRepo,
  creditRewardRepo: CreditRewardRepo,
  creditRewardCommander: CreditRewardCommander,
  paidAccountRepo: PaidAccountRepo,
  accountLockHelper: AccountLockHelper,
  eventTrackingCommander: AccountEventTrackingCommander,
  airbrake: AirbrakeNotifier)
    extends Logging {

  private def rewardsPreventedBy(kind: RewardChecklistKind): Set[RewardChecklistKind] = kind match {
    case _ => Set.empty
  }
  private def processChecklistRewards(orgId: Id[Organization]): Unit = accountLockHelper.maybeSessionWithAccountLock(orgId) { implicit session =>
    val accountId = paidAccountRepo.getAccountId(orgId)
    val currentRewards: Set[RewardChecklistKind] = creditRewardRepo.getByAccount(accountId).map(_.reward.kind).collect { case k: RewardChecklistKind => k }
    val blockedRewards = currentRewards.flatMap(rewardsPreventedBy)
    assert(blockedRewards.map(x => x: RewardKind).subsetOf(RewardKind.deprecated), "We should not block non-deprecated rewards: " + (blockedRewards.map(x => x: RewardKind) -- RewardKind.deprecated))

    RewardKind.allActive.collect {
      case k: RewardChecklistKind if !currentRewards.contains(k) && !blockedRewards.contains(k) =>
        creditRewardCommander.initializeChecklistReward(orgId, k)
    }
  }

  def checkAccount(orgId: Id[Organization]): Unit = {
    processChecklistRewards(orgId)
  }

  def checkAccounts(modulus: Int = 7): Unit = {
    // we are going to process ~1/modulus of the orgs, based on the date
    val partition = clock.now.getDayOfYear % modulus
    val orgIds = db.readOnlyReplica { implicit session => paidAccountRepo.getIdSubsetByModulus(modulus, partition) }
    orgIds.foreach(checkAccount)
  }

}
