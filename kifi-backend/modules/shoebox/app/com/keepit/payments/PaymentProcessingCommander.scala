package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.Organization
import com.keepit.common.concurrent.{ ReactiveLock, FutureHelpers }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }

import com.google.inject.{ ImplementedBy, Inject, Singleton }

import play.api.libs.json.JsNull

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@ImplementedBy(classOf[PaymentProcessingCommanderImpl])
trait PaymentProcessingCommander {
  def processAllBilling(): Future[Map[Id[Organization], DollarAmount]]
  def forceChargeAccount(account: PaidAccount, amount: DollarAmount, session: RWSession, description: Option[String]): Future[DollarAmount] //not private for admin use
}

@Singleton
class PaymentProcessingCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  accountEventRepo: AccountEventRepo,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  clock: Clock,
  accountLockHelper: AccountLockHelper,
  stripeClient: StripeClient,
  airbrake: AirbrakeNotifier,
  implicit val defaultContext: ExecutionContext)
    extends PaymentProcessingCommander with Logging {

  private val MAX_BALANCE = DollarAmount.wholeDollars(-100) //if you owe us more than $100 we will charge your card even if your billing cycle is not up
  private val MIN_BALANCE = DollarAmount.wholeDollars(-1) //if you are carrying a balance of less then one dollar you will not be charged (to much cost overhead)

  val processingLock = new ReactiveLock(1)

  def processAllBilling(): Future[Map[Id[Organization], DollarAmount]] = processingLock.withLockFuture {
    val relevantAccounts = db.readOnlyMaster { implicit session => paidAccountRepo.getRipeAccounts(MAX_BALANCE, clock.now.minusMonths(1)) } //we check at least monthly, even for accounts on longer billing cycles + accounts with large balance
    FutureHelpers.map(relevantAccounts.map { account =>
      account.orgId -> processAccount(account)
    }.toMap)
  }

  private[payments] def processAccount(account: PaidAccount): Future[DollarAmount] = accountLockHelper.maybeSessionWithAccountLock(account.orgId) { implicit session =>
    log.info(s"[PPC][${account.orgId}] Starting Processing")
    val plan = paidPlanRepo.get(account.planId)
    val billingCycleElapsed = account.billingCycleStart.plusMonths(plan.billingCycle.month).isAfter(clock.now)
    val maxBalanceExceeded = account.credit.cents < MAX_BALANCE.cents
    val shouldProcess = !account.frozen && (billingCycleElapsed || maxBalanceExceeded)
    if (shouldProcess) {
      val newBillingCycleStart = account.billingCycleStart.plusMonths(plan.billingCycle.month)
      val fullCyclePrice = DollarAmount(account.activeUsers * plan.pricePerCyclePerUser.cents)
      val updatedAccountPreCharge = if (billingCycleElapsed) account.withReducedCredit(fullCyclePrice).withCycleStart(newBillingCycleStart) else account

      if (account.credit.cents < MIN_BALANCE.cents) {
        val chargeAmount = DollarAmount(-1 * account.credit.cents)
        log.info(s"[PPC][${account.orgId}] Going to charge $chargeAmount")
        val description = if (maxBalanceExceeded) s"Max balance exceeded charge for org ${account.orgId} of amount $chargeAmount" else s"Regular charge for org ${account.orgId} of amount $chargeAmount"
        forceChargeAccount(account, chargeAmount, session, Some(description))
      } else {
        log.info(s"[PPC][${account.orgId}] Not Charging. Balance less than $$1 (${account.credit})")
        paidAccountRepo.save(updatedAccountPreCharge)
        Future.successful(DollarAmount.ZERO)
      }
    } else {
      Future.successful(DollarAmount.ZERO)
    }
  }.getOrElse(Future.successful(DollarAmount.ZERO))

  def forceChargeAccount(account: PaidAccount, amount: DollarAmount, session: RWSession, descriptionOpt: Option[String]): Future[DollarAmount] = {
    implicit val s = session
    paymentMethodRepo.getDefault(account.id.get) match {
      case Some(pm) => {
        val description = descriptionOpt.getOrElse("Forced charge for org ${account.orgId} of $amount")
        stripeClient.processCharge(amount, pm.stripeToken, description).map {
          case StripeChargeSuccess(amount, chargeId) => {
            paidAccountRepo.save(account.withIncreasedCredit(amount))
            accountEventRepo.save(AccountEvent(
              eventGroup = EventGroup(),
              eventTime = clock.now(),
              accountId = account.id.get,
              billingRelated = true,
              whoDunnit = None,
              whoDunnitExtra = JsNull,
              kifiAdminInvolved = None,
              action = AccountEventAction.PlanBillingCharge(),
              creditChange = amount,
              paymentMethod = pm.id,
              paymentCharge = Some(amount),
              memo = None,
              chargeId = Some(chargeId)
            ))
            log.info(s"[PPC][${account.orgId}] Processed charge fro amount $amount: $description")
            amount
          }
          case StripeChargeFailure(code, message) => {
            airbrake.notify(s"Stripe error charging org ${account.orgId}: $code, $message")
            throw new Exception(message)
          }
        }
      }
      case None => {
        airbrake.notify(s"Missing default payment method for org ${account.orgId}")
        throw new Exception("missing_default_payment_method")
      }
    }
  }

}
