package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.{ User, OrganizationRepo, Organization }
import com.keepit.payments.CreditCodeFail.{ CreditCodeAlreadyBurnedException, CreditCodeAbuseException, NoPaidAccountException, CreditCodeNotFoundException }
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json.JsNull

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[CreditRewardCommanderImpl])
trait CreditRewardCommander {
  def getOrCreateReferralCode(orgId: Id[Organization]): CreditCode
  def applyCreditCode(req: CreditCodeApplyRequest): Try[CreditCodeRewards]
}

@Singleton
class CreditRewardCommanderImpl @Inject() (
  db: Database,
  orgRepo: OrganizationRepo,
  creditCodeInfoRepo: CreditCodeInfoRepo,
  creditRewardRepo: CreditRewardRepo,
  accountRepo: PaidAccountRepo,
  clock: Clock,
  eventCommander: AccountEventTrackingCommander,
  implicit val defaultContext: ExecutionContext)
    extends CreditRewardCommander with Logging {

  def getOrCreateReferralCode(orgId: Id[Organization]): CreditCode = {
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val creditCodeInfo = creditCodeInfoRepo.getByOrg(orgId).getOrElse {
        val creditCodeInfo = CreditCodeInfo(
          code = CreditCode(RandomStringUtils.randomAlphanumeric(20)),
          kind = CreditCodeKind.OrganizationReferral,
          status = CreditCodeStatus.Open,
          referrer = Some(CreditCodeReferrer.fromOrg(org))
        )
        creditCodeInfoRepo.save(creditCodeInfo)
      }
      creditCodeInfo.code
    }
  }
  def applyCreditCode(req: CreditCodeApplyRequest): Try[CreditCodeRewards] = db.readWrite { implicit session =>
    for {
      creditCodeInfo <- creditCodeInfoRepo.getByCode(req.code).map(Success(_)).getOrElse(Failure(CreditCodeNotFoundException(req.code)))
      accountId <- req.orgId.map(accountRepo.getAccountId(_)).map(Success(_)).getOrElse(Failure(NoPaidAccountException(req.applierId, req.orgId)))
      _ <- if (creditCodeInfo.referrer.exists(r => r.organizationId.isDefined && r.organizationId == req.orgId)) Failure(CreditCodeAbuseException(req)) else Success(true)
      _ <- if (creditCodeInfo.isSingleUse && creditRewardRepo.getByCreditCode(creditCodeInfo.code).nonEmpty) Failure(CreditCodeAlreadyBurnedException(req.code)) else Success(true)
    } yield {
      creditCodeInfo.kind match {
        case CreditCodeKind.Coupon =>
          val targetReward = Reward(RewardKind.Coupon)(RewardKind.Coupon.Used)(None)
          val targetCreditReward = internCreditReward(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.credit,
            applied = None,
            reward = targetReward,
            unrepeatable = None,
            code = Some(UsedCreditCode(creditCodeInfo, req.applierId))
          ), req.applierId)
          CreditCodeRewards(target = targetCreditReward, referrer = None)

        case CreditCodeKind.OrganizationReferral =>
          val targetReward = Reward(RewardKind.OrganizationCreation)(RewardKind.OrganizationCreation.Created)(req.orgId.get)
          val targetCreditReward = internCreditReward(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.credit,
            applied = None,
            reward = targetReward,
            unrepeatable = req.orgId.map(UnrepeatableRewardKey.ForOrganization),
            code = Some(UsedCreditCode(creditCodeInfo, req.applierId))
          ), req.applierId)

          val referrerReward = Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Created)(req.orgId.get)
          val referrerCreditReward = internCreditReward(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.referrerCredit.get,
            applied = None,
            reward = referrerReward,
            unrepeatable = req.orgId.map(UnrepeatableRewardKey.ForOrganization),
            code = Some(UsedCreditCode(creditCodeInfo, req.applierId))
          ), req.applierId)
          CreditCodeRewards(target = targetCreditReward, referrer = Some(referrerCreditReward))
      }
    }
  }

  def internCreditReward(cr: CreditReward, userAttribution: Id[User])(implicit session: RWSession): CreditReward = {
    require(cr.id.isEmpty)
    val creditReward = creditRewardRepo.save(cr)

    val rewardNeedsToBeApplied = creditReward.reward.status == creditReward.reward.kind.applicable
    if (!rewardNeedsToBeApplied) creditReward
    else {
      val account = accountRepo.get(creditReward.accountId)
      accountRepo.save(account.withIncreasedCredit(creditReward.credit))
      val rewardCreditEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        whoDunnit = Some(userAttribution),
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.RewardCredit(creditReward.id.get),
        creditChange = creditReward.credit,
        paymentMethod = None,
        paymentCharge = None,
        memo = None,
        chargeId = None
      ))
      creditRewardRepo.save(creditReward.withAppliedEvent(rewardCreditEvent))
    }
  }
}
