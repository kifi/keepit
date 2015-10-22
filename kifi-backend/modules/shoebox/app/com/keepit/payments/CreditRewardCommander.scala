package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.{ OrganizationRepo, Organization }
import com.keepit.payments.CreditCodeFail.{ CreditCodeNotApplicable, NoPaidAccountException, CreditCodeNotFoundException }
import org.apache.commons.lang3.RandomStringUtils

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
      appliable <- if (creditCodeInfo.referrer.exists(r => r.organizationId.isDefined && r.organizationId == req.orgId)) Failure(CreditCodeNotApplicable(req)) else Success(true)
    } yield {
      creditCodeInfo.kind match {
        case CreditCodeKind.Coupon => ???
        case CreditCodeKind.OrganizationReferral =>
          val targetReward = Reward(RewardKind.OrganizationCreation)(RewardKind.OrganizationCreation.Created)(req.orgId.get)
          val targetCreditReward = creditRewardRepo.save(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.credit,
            applied = None,
            reward = targetReward,
            unrepeatable = req.orgId.map(UnrepeatableRewardKey.ForOrganization),
            code = Some(UsedCreditCode(creditCodeInfo, req.applierId))
          ))

          val referrerReward = Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Created)(req.orgId.get)
          val referrerCreditReward = Some(creditRewardRepo.save(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.referrerCredit.get,
            applied = None,
            reward = referrerReward,
            unrepeatable = req.orgId.map(UnrepeatableRewardKey.ForOrganization),
            code = Some(UsedCreditCode(creditCodeInfo, req.applierId))
          )))
          CreditCodeRewards(target = targetCreditReward, referrer = referrerCreditReward)
      }
    }
  }
}
