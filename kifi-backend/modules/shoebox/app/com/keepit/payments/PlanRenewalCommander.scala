package com.keepit.payments

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.util.DescriptionElements
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.slack.models.{ SlackMessageRequest, SlackChannelName }
import play.api.libs.json.JsNull

import scala.concurrent.{ ExecutionContext }
import scala.util.{ Failure, Success, Try }
import com.keepit.common.time._

case class UnnecessaryPlanRenewalException(account: PaidAccount) extends Exception(s"Organization ${account.orgId}'s plan should not renew before ${account.planRenewal}.")

@ImplementedBy(classOf[PlanRenewalCommanderImpl])
trait PlanRenewalCommander {
  def processDueRenewals(): Unit
}

@Singleton
class PlanRenewalCommanderImpl @Inject() (
  db: Database,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  clock: Clock,
  accountLockHelper: AccountLockHelper,
  airbrake: AirbrakeNotifier,
  eventCommander: AccountEventTrackingCommander,
  implicit val defaultContext: ExecutionContext,
  inhouseSlackClient: InhouseSlackClient)
    extends PlanRenewalCommander with Logging {

  def processDueRenewals(): Unit = synchronized {
    val relevantAccounts = db.readOnlyMaster { implicit session => paidAccountRepo.getRenewable() }
    if (relevantAccounts.nonEmpty) {
      inhouseSlackClient.sendToSlack(InhouseSlackChannel.BILLING_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(s"Renewing plans for ${relevantAccounts.length} accounts.")))
      val renewed = relevantAccounts.count(renewPlan(_) match {
        case Success(_) => true
        case Failure(error) =>
          airbrake.notify(error)
          false
      })
      inhouseSlackClient.sendToSlack(InhouseSlackChannel.BILLING_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(s"$renewed/${relevantAccounts.length} plans were successfully renewed.")))
    }
  }

  private[payments] def renewPlan(account: PaidAccount): Try[AccountEvent] = {
    if (!account.frozen) {
      accountLockHelper.maybeSessionWithAccountLock(account.orgId) { implicit session =>
        val now = clock.now()
        val plan = paidPlanRepo.get(account.planId)
        if (account.planRenewal isBefore now) {
          val nextPlanRenewal = account.planRenewal plusMonths plan.billingCycle.months
          val fullCyclePrice = plan.pricePerCyclePerUser * account.activeUsers
          val renewedAccount = paidAccountRepo.save(
            account.withReducedCredit(fullCyclePrice).withPlanRenewal(nextPlanRenewal).withPaymentDueAt(Some(now))
          )
          val billingEvent = eventCommander.track(AccountEvent(
            eventTime = clock.now(),
            accountId = account.id.get,
            whoDunnit = None,
            whoDunnitExtra = JsNull,
            kifiAdminInvolved = None,
            action = AccountEventAction.PlanRenewal.from(plan, account),
            creditChange = renewedAccount.credit - account.credit,
            paymentMethod = None,
            paymentCharge = None,
            memo = None,
            chargeId = None
          ))
          Success(billingEvent)
        } else Failure(UnnecessaryPlanRenewalException(account))
      } getOrElse Failure(LockedAccountException(account.orgId))
    } else Failure(FrozenAccountException(account.orgId))
  }
}
