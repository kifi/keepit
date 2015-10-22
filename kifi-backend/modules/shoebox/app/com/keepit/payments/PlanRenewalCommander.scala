package com.keepit.payments

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.Organization
import org.joda.time.DateTime
import play.api.libs.json.JsNull

import scala.concurrent.{ ExecutionContext }
import scala.util.{ Failure, Success, Try }
import com.keepit.common.time._

case class UnexpectedPlanRenewalException(orgId: Id[Organization], renewsAt: DateTime) extends Exception(s"Organization $orgId's plan should not renew before $renewsAt.")

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
  implicit val defaultContext: ExecutionContext)
    extends PlanRenewalCommander with Logging {

  def processDueRenewals(): Unit = synchronized {
    val relevantAccounts = db.readOnlyMaster { implicit session => paidAccountRepo.getRenewable() }
    if (relevantAccounts.length > 0) {
      eventCommander.reportToSlack(s"Renewing plans for ${relevantAccounts.length} accounts.")
      val renewed = relevantAccounts.count(renewPlan(_) match {
        case Success(_) => true
        case Failure(error) =>
          airbrake.notify(error)
          false
      })
      eventCommander.reportToSlack(s"$renewed/${relevantAccounts.length} plans were successfully renewed.")
    }
  }

  private[payments] def renewPlan(account: PaidAccount): Try[AccountEvent] = {
    if (!account.frozen) {
      accountLockHelper.maybeSessionWithAccountLock(account.orgId) { implicit session =>
        val now = clock.now()
        val plan = paidPlanRepo.get(account.planId)
        val renewsAt = account.billingCycleStart plusMonths plan.billingCycle.month
        if (renewsAt isBefore now) {
          val newBillingCycleStart = account.billingCycleStart.plusMonths(plan.billingCycle.month)
          val fullCyclePrice = plan.pricePerCyclePerUser * account.activeUsers
          val paymentDueAt = if (fullCyclePrice > DollarAmount.ZERO) Some(now) else account.paymentDueAt
          val renewedAccount = paidAccountRepo.save(
            account.withReducedCredit(fullCyclePrice).withCycleStart(newBillingCycleStart).withPaymentDueAt(paymentDueAt)
          )
          val billingEvent = eventCommander.track(AccountEvent(
            eventTime = clock.now(),
            accountId = account.id.get,
            billingRelated = true,
            whoDunnit = None,
            whoDunnitExtra = JsNull,
            kifiAdminInvolved = None,
            action = AccountEventAction.PlanBilling.from(plan, account),
            creditChange = renewedAccount.credit - account.credit,
            paymentMethod = None,
            paymentCharge = None,
            memo = None,
            chargeId = None
          ))
          Success(billingEvent)
        } else Failure(UnexpectedPlanRenewalException(account.orgId, renewsAt))
      } getOrElse Failure(LockedAccountException(account.orgId))
    } else Failure(FrozenAccountException(account.orgId))
  }
}
