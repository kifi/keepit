package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.{ OrganizationRepo, Organization }
import org.apache.commons.lang3.RandomStringUtils

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[CreditRewardCommanderImpl])
trait CreditRewardCommander {
  def getOrCreateReferralCode(orgId: Id[Organization]): CreditCode
  def createCreditCodeRewards(info: CreditCodeInfo): CreditCodeRewards
}

@Singleton
class CreditRewardCommanderImpl @Inject() (
  db: Database,
  orgRepo: OrganizationRepo,
  creditCodeInfoRepo: CreditCodeInfoRepo,
  clock: Clock,
  eventCommander: AccountEventTrackingCommander,
  implicit val defaultContext: ExecutionContext)
    extends CreditRewardCommander with Logging {

  private val orgReferralCredit = DollarAmount.dollars(420)

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
  def createCreditCodeRewards(info: CreditCodeInfo): CreditCodeRewards = ???
}
