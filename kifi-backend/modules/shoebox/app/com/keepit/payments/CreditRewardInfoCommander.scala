package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, LocalPostOffice, ElectronicMail }
import com.keepit.common.time._
import com.keepit.model.{ OrganizationExperimentType, OrganizationExperiment, OrganizationExperimentRepo, NotificationCategory, UserEmailAddressRepo, OrganizationRole, OrganizationMembershipRepo, User, OrganizationRepo, Organization }
import com.keepit.payments.CreditRewardFail._
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json.JsNull

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[CreditRewardInfoCommanderImpl])
trait CreditRewardInfoCommander {
  def getRewardsByOrg(orgId: Id[Organization]): Seq[CreditReward]
}

@Singleton
class CreditRewardInfoCommanderImpl @Inject() (
  db: Database,
  orgRepo: OrganizationRepo,
  creditCodeInfoRepo: CreditCodeInfoRepo,
  creditRewardRepo: CreditRewardRepo,
  accountRepo: PaidAccountRepo,
  clock: Clock,
  orgExpRepo: OrganizationExperimentRepo,
  implicit val defaultContext: ExecutionContext)
    extends CreditRewardInfoCommander with Logging {

  def getRewardsByOrg(orgId: Id[Organization]): Seq[CreditReward] = db.readOnlyMaster { implicit session =>
    val creditRewards = creditRewardRepo.getByAccount(accountRepo.getByOrgId(orgId).id.get)
      .toList.sortBy(_.applied.nonEmpty)
  }
}
