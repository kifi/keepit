package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, LocalPostOffice, ElectronicMail }
import com.keepit.common.time._
import com.keepit.model.{ OrganizationExperimentType, OrganizationExperiment, OrganizationExperimentRepo, NotificationCategory, UserEmailAddressRepo, OrganizationRole, OrganizationMembershipRepo, User, OrganizationRepo, Organization }
import com.keepit.payments.CreditRewardFail._
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json.JsNull

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[CreditRewardCommanderImpl])
trait CreditRewardCommander {
  // Generic API for creating a credit reward (use for one-off rewards, like the org creation bonus)
  def createCreditReward(cr: CreditReward, userAttribution: Option[Id[User]])(implicit session: RWSession): Try[CreditReward]

  // CreditCode methods, open DB sessions (intended to be called directly from controllers)
  def getOrCreateReferralCode(orgId: Id[Organization]): CreditCode
  def applyCreditCode(req: CreditCodeApplyRequest): Try[CreditCodeRewards]
  def adminCreateCreditCode(codeTemplate: CreditCodeInfo): CreditCodeInfo

  // In-place evolutions of existing rewards
  def registerUpgradedAccount(orgId: Id[Organization])(implicit session: RWSession): Set[CreditReward]
}

@Singleton
class CreditRewardCommanderImpl @Inject() (
  db: Database,
  orgRepo: OrganizationRepo,
  creditCodeInfoRepo: CreditCodeInfoRepo,
  creditRewardRepo: CreditRewardRepo,
  accountRepo: PaidAccountRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  emailAddressRepo: UserEmailAddressRepo,
  clock: Clock,
  eventCommander: AccountEventTrackingCommander,
  accountLockHelper: AccountLockHelper,
  postOffice: LocalPostOffice,
  orgExpRepo: OrganizationExperimentRepo,
  implicit val defaultContext: ExecutionContext)
    extends CreditRewardCommander with Logging {

  private val newOrgReferralCredit = DollarAmount.dollars(100)
  private val orgReferrerCredit = DollarAmount.dollars(100)

  def adminCreateCreditCode(codeTemplate: CreditCodeInfo): CreditCodeInfo = db.readWrite { implicit session =>
    val base = codeTemplate.code.value
    val suffixes = "" +: Iterator.continually("-" + RandomStringUtils.randomNumeric(2)).take(9).toStream
    suffixes.map { suf =>
      creditCodeInfoRepo.create(codeTemplate.copy(code = CreditCode.normalize(base + suf + "-" + codeTemplate.credit.toCents / 100)))
    }.dropWhile(_.isFailure)
      .headOption.getOrElse(throw new Exception(s"Could not find an unused code for $base, even with random suffixes")).get
  }
  def getOrCreateReferralCode(orgId: Id[Organization]): CreditCode = {
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val creditCodeInfo = creditCodeInfoRepo.getByOrg(orgId).getOrElse {
        // Try to create a referral code, starting with the raw normalized
        // handle (abbreviated maybe), and successively adding random digits
        // to the end until it works.
        val base = org.primaryHandle.get.normalized.value.take(20)
        val suffixes = "" +: Iterator.continually("-" + RandomStringUtils.randomNumeric(2)).take(9).toStream
        suffixes.map { suf =>
          creditCodeInfoRepo.create(CreditCodeInfo(
            code = CreditCode.normalize(base + suf + "-" + newOrgReferralCredit.toCents / 100),
            kind = CreditCodeKind.OrganizationReferral,
            credit = newOrgReferralCredit,
            status = CreditCodeStatus.Open,
            referrer = Some(CreditCodeReferrer(org.ownerId, Some(orgId), orgReferrerCredit))
          ))
        }.dropWhile(_.isFailure).head.get
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
      rewards <- createRewardsFromCreditCode(creditCodeInfo, accountId, req.applierId, req.orgId)
    } yield {
      (creditCodeInfo.referrer.flatMap(_.organizationId), req.orgId, creditCodeInfo.kind) match {
        case (Some(referrerOrgId), Some(referredOrgId), CreditCodeKind.OrganizationReferral) =>
          if (orgExpRepo.hasExperiment(referrerOrgId, OrganizationExperimentType.FAKE) && orgExpRepo.hasExperiment(referredOrgId, OrganizationExperimentType.FAKE)) {
            sendReferralCodeAppliedEmail(referrerOrgId, referredOrgId)
          }
        case _ =>
      }

      rewards
    }
  }

  private def sendReferralCodeAppliedEmail(referrerOrgId: Id[Organization], referredOrgId: Id[Organization])(implicit session: RWSession): Unit = {
    val referredOrg = orgRepo.get(referredOrgId)
    val referrerOrg = orgRepo.get(referrerOrgId)
    val adminEmails = orgMembershipRepo.getByRole(referrerOrgId, OrganizationRole.ADMIN).flatMap(adminId => Try(emailAddressRepo.getByUser(adminId)).toOption)
    val subject = s"Your team's referral code was used by ${referredOrg.name} on Kifi"
    val htmlBody =
      s"""
         |Your <a href="https://www.kifi.com/${referrerOrg.handle.value}">${referrerOrg.name}</a> team's referral code was used by <a href="https://www.kifi.com/${referredOrg.handle.value}">${referredOrg.name}</a>. If they upgrade to a standard plan on Kifi,
         |you'll earn a ${orgReferrerCredit.toDollarString} credit for your team. Thank you so much for spreading the word about Kifi with great teams like ${referredOrg.name}!
       """.stripMargin
    val textBody =
      s"""
         |Your ${referrerOrg.name} team's referral code was used by ${referredOrg.name}. If they upgrade to a standard plan on Kifi,
         |you'll earn a ${orgReferrerCredit.toDollarString} credit for your team. Thank you so much for spreading the word about Kifi with great teams like ${referredOrg.name}!
       """.stripMargin
    postOffice.sendMail(ElectronicMail(
      from = SystemEmailAddress.NOTIFICATIONS,
      to = adminEmails,
      subject = subject,
      htmlBody = htmlBody,
      textBody = Some(textBody),
      category = NotificationCategory.NonUser.BILLING
    ))
  }

  def createCreditReward(cr: CreditReward, userAttribution: Option[Id[User]])(implicit session: RWSession): Try[CreditReward] = {
    creditRewardRepo.create(cr).map { creditReward => finalizeCreditReward(creditReward, userAttribution) }
  }

  private def createRewardsFromCreditCode(creditCodeInfo: CreditCodeInfo, accountId: Id[PaidAccount], userId: Id[User], orgId: Option[Id[Organization]])(implicit session: RWSession): Try[CreditCodeRewards] = {
    val unfinalizedRewardsTry = creditCodeInfo.kind match {
      case CreditCodeKind.Coupon =>
        val targetReward = Reward(RewardKind.Coupon)(RewardKind.Coupon.Used)(None)
        for {
          targetCreditReward <- creditRewardRepo.create(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.credit,
            applied = None,
            reward = targetReward,
            unrepeatable = None,
            code = Some(UsedCreditCode(creditCodeInfo, userId))
          ))
        } yield {
          CreditCodeRewards(target = targetCreditReward, referrer = None)
        }

      case CreditCodeKind.Promotion =>
        val targetReward = Reward(RewardKind.ReferralApplied)(RewardKind.ReferralApplied.Applied)(creditCodeInfo.code)
        for {
          targetCreditReward <- creditRewardRepo.create(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.credit,
            applied = None,
            reward = targetReward,
            unrepeatable = Some(UnrepeatableRewardKey.WasReferred(orgId.get)),
            code = Some(UsedCreditCode(creditCodeInfo, userId))
          ))
        } yield CreditCodeRewards(target = targetCreditReward, referrer = None)

      case CreditCodeKind.OrganizationReferral =>
        val targetReward = Reward(RewardKind.ReferralApplied)(RewardKind.ReferralApplied.Applied)(creditCodeInfo.code)
        val referrerReward = Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Created)(orgId.get)
        for {
          targetCreditReward <- creditRewardRepo.create(CreditReward(
            accountId = accountId,
            credit = creditCodeInfo.credit,
            applied = None,
            reward = targetReward,
            unrepeatable = Some(UnrepeatableRewardKey.WasReferred(orgId.get)),
            code = Some(UsedCreditCode(creditCodeInfo, userId))
          ))

          referrer <- creditCodeInfo.referrer.map(Success(_)).getOrElse(Failure(NoReferrerException(creditCodeInfo)))
          referrerAccount <- referrer.organizationId.map(accountRepo.getAccountId(_)).map(Success(_)).getOrElse(Failure(NoPaidAccountException(referrer.userId, referrer.organizationId)))
          referrerCreditReward <- creditRewardRepo.create(CreditReward(
            accountId = referrerAccount,
            credit = referrer.credit,
            applied = None,
            reward = referrerReward,
            unrepeatable = Some(UnrepeatableRewardKey.Referred(orgId.get)),
            code = Some(UsedCreditCode(creditCodeInfo, userId))
          ))
        } yield CreditCodeRewards(target = targetCreditReward, referrer = Some(referrerCreditReward))
    }
    unfinalizedRewardsTry.map { rewards =>
      if (creditCodeInfo.isSingleUse) creditCodeInfoRepo.close(creditCodeInfo)
      CreditCodeRewards(
        target = finalizeCreditReward(rewards.target, Some(userId)),
        referrer = rewards.referrer.map(finalizeCreditReward(_, Some(userId)))
      )
    }
  }

  def registerUpgradedAccount(orgId: Id[Organization])(implicit session: RWSession): Set[CreditReward] = {
    val currentReward = Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Created)(orgId)
    val evolvedReward = Reward(RewardKind.OrganizationReferral)(RewardKind.OrganizationReferral.Upgraded)(orgId)
    val crs = creditRewardRepo.getByReward(currentReward)
    assert(crs.size <= 1, s"Somehow there are multiple referral rewards for $orgId! $crs")
    val rewards = crs.map { crToEvolve =>
      finalizeCreditReward(crToEvolve.withReward(evolvedReward), None)
    }
    crs.headOption.foreach { crToEvolve =>
      val referrerOrgId = accountRepo.get(crToEvolve.accountId).orgId
      if (orgExpRepo.hasExperiment(orgId, OrganizationExperimentType.FAKE) && orgExpRepo.hasExperiment(referrerOrgId, OrganizationExperimentType.FAKE)) {
        sendReferredAccountUpgradedEmail(orgId, referrerOrgId)
      }
    }
    rewards
  }

  private def sendReferredAccountUpgradedEmail(referredOrgId: Id[Organization], referrerOrgId: Id[Organization])(implicit session: RWSession): ElectronicMail = {
    val referredOrg = orgRepo.get(referredOrgId)
    val referrerOrg = orgRepo.get(referrerOrgId)
    val adminEmails = orgMembershipRepo.getByRole(referrerOrgId, OrganizationRole.ADMIN).flatMap(adminId => Try(emailAddressRepo.getByUser(adminId)).toOption)
    val subject = s"You earned a ${orgReferrerCredit.toDollarString} credit for ${referrerOrg.name} on Kifi"
    val htmlBody =
      s"""
         |Your team, <a href="https://www.kifi.com/${referrerOrg.handle.value}">${referrerOrg.name}</a>, earned a ${orgReferrerCredit.toDollarString} credit from <a href="https://www.kifi.com/${referredOrg.handle.value}">${referredOrg.name}</a>.
         |We've added it to your <a href="https://www.kifi.com/${referrerOrg.handle.value}/settings/plan">team balance</a>. Thank you so much for spreading the word
         |about Kifi with great teams like ${referredOrg.name}!
       """.stripMargin
    val textBody =
      s"""
         |Your team, ${referrerOrg.name}, earned a $$100 credit from ${referredOrg.name}. We've added it to your team balance.
         |Thank you so much for spreading the word about Kifi with great teams like ${referredOrg.name}!
       """.stripMargin
    postOffice.sendMail(ElectronicMail(
      from = SystemEmailAddress.NOTIFICATIONS,
      to = adminEmails,
      subject = subject,
      htmlBody = htmlBody,
      textBody = Some(textBody),
      category = NotificationCategory.NonUser.BILLING
    ))
  }

  private def finalizeCreditReward(creditReward: CreditReward, userAttribution: Option[Id[User]])(implicit session: RWSession): CreditReward = {
    require(creditReward.id.nonEmpty, s"$creditReward has not been persisted to the db")
    require(creditReward.applied.isEmpty, s"$creditReward has already been applied")
    val rewardNeedsToBeApplied = creditReward.reward.status == creditReward.reward.kind.applicable
    if (!rewardNeedsToBeApplied) creditReward
    else {
      val orgId = accountRepo.get(creditReward.accountId).orgId // todo(Léo): we should be able to lock using the account id directly
      accountLockHelper.maybeWithAccountLock(orgId, attempts = 3) {
        require(creditRewardRepo.get(creditReward.id.get).applied.isEmpty, s"$creditReward has already been applied") // check after locking
        val account = accountRepo.get(creditReward.accountId)
        accountRepo.save(account.withIncreasedCredit(creditReward.credit))
        val rewardCreditEvent = eventCommander.track(AccountEvent(
          eventTime = clock.now(),
          accountId = account.id.get,
          whoDunnit = userAttribution,
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
      } getOrElse { throw new LockedAccountException(orgId) }
    }
  }

}
