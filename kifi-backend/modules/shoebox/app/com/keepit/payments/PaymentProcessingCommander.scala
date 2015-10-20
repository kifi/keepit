package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ Organization }
import com.keepit.common.concurrent.{ ReactiveLock }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.core._

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.kifi.macros.json

import play.api.libs.json.{ JsNull }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Failure

@ImplementedBy(classOf[PaymentProcessingCommanderImpl])
trait PaymentProcessingCommander {
  def processAllBilling(): Future[Unit]
  def forceChargeAccount(orgId: Id[Organization], amount: DollarAmount): Future[Option[AccountEvent]]
  def processAccount(account: PaidAccount): Future[Seq[AccountEvent]]

  private[payments] val MIN_BALANCE: DollarAmount
  private[payments] val MAX_BALANCE: DollarAmount

}

@Singleton
class PaymentProcessingCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  paidAccountRepo: PaidAccountRepo,
  paidPlanRepo: PaidPlanRepo,
  clock: Clock,
  accountLockHelper: AccountLockHelper,
  stripeClient: StripeClient,
  airbrake: AirbrakeNotifier,
  eventCommander: AccountEventTrackingCommander,
  implicit val defaultContext: ExecutionContext)
    extends PaymentProcessingCommander with Logging {

  private[payments] val MAX_BALANCE = DollarAmount.dollars(1000) //if you owe us more than $100 we will charge your card even if your billing cycle is not up
  private[payments] val MIN_BALANCE = DollarAmount.dollars(1) //if you are carrying a balance of less then one dollar you will not be charged (to much cost overhead)

  private val processingLock = new ReactiveLock(2)

  def processAllBilling(): Future[Unit] = processingLock.withLockFuture {
    val relevantAccounts = db.readOnlyMaster { implicit session => paidAccountRepo.getRipeAccounts(MAX_BALANCE, clock.now.minusMonths(1)) } //we check at least monthly, even for accounts on longer billing cycles + accounts with large balance
    if (relevantAccounts.length > 0) eventCommander.reportToSlack(s"Processing Payments. ${relevantAccounts.length} orgs to check.")
    Future.sequence(relevantAccounts.map { account =>
      processAccount(account).imap(_ => ()) recover {
        case e: Exception => {
          val message = s"Fatal error processing Org ${account.orgId}"
          log.error(message, e)
          airbrake.notify(message, e)
        }
      }
    }).imap(_ => ())
  }

  def processAccount(account: PaidAccount): Future[Seq[AccountEvent]] = processingLock.withLockFuture {
    accountLockHelper.maybeWithAccountLockAsync(account.orgId) {
      log.info(s"[PPC][${account.orgId}] Starting Processing")
      if (!account.frozen) {
        val plan = db.readOnlyMaster { implicit session => paidPlanRepo.get(account.planId) }
        val billingCycleElapsed = account.billingCycleStart.plusMonths(plan.billingCycle.month).isBefore(clock.now)
        val maxBalanceExceeded = account.owed > MAX_BALANCE
        val chargeRequired = (account.paymentStatus == PaymentStatus.Required)
        val shouldProcess = chargeRequired || billingCycleElapsed || maxBalanceExceeded
        if (shouldProcess) {
          val (billedAccount, billingEvent) = if (billingCycleElapsed) billAccount(account, plan) else (account, None)
          if (billedAccount.owed > MIN_BALANCE) {
            chargeAccount(billedAccount, billedAccount.owed).map(_._2) recover {
              case error: com.stripe.exception.StripeException => None
            } map {
              chargeEvent => Seq(billingEvent, chargeEvent).flatten
            }
          } else {
            val (_, lowBalanceIgnoredEvent) = ignoreLowBalance(billedAccount)
            Future.successful(billingEvent.toSeq :+ lowBalanceIgnoredEvent)
          }
        } else Future.successful(Seq.empty)
      } else Future.successful(Seq.empty)
    } getOrElse { throw new LockedAccountException(account.orgId) }
  }

  private def billAccount(account: PaidAccount, plan: PaidPlan): (PaidAccount, Some[AccountEvent]) = {
    db.readWrite { implicit session =>
      val newBillingCycleStart = account.billingCycleStart.plusMonths(plan.billingCycle.month)
      val fullCyclePrice = DollarAmount(account.activeUsers * plan.pricePerCyclePerUser.cents)
      val billedAccount = paidAccountRepo.save(account.withReducedCredit(fullCyclePrice).withCycleStart(newBillingCycleStart))
      val billingEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        billingRelated = true,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.PlanBilling.from(plan, account),
        creditChange = billedAccount.credit - account.credit,
        paymentMethod = None,
        paymentCharge = None,
        memo = None,
        chargeId = None
      ))
      (billedAccount, Some(billingEvent))
    }
  }

  private def ignoreLowBalance(account: PaidAccount): (PaidAccount, AccountEvent) = {
    db.readWrite { implicit session =>
      val updatedAccount = if (account.paymentStatus == PaymentStatus.Required) paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Ok)) else account
      val lowBalanceIgnoredEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        billingRelated = true,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.LowBalanceIgnored(account.owed),
        creditChange = DollarAmount.ZERO,
        paymentMethod = None,
        paymentCharge = None,
        memo = None,
        chargeId = None
      ))
      (updatedAccount, lowBalanceIgnoredEvent)
    }
  }

  private def chargeAccount(account: PaidAccount, amount: DollarAmount): Future[(PaidAccount, Option[AccountEvent])] = {
    account.paymentStatus match {
      case PaymentStatus.Ok | PaymentStatus.Required => {

        db.readOnlyMaster { implicit session => paymentMethodRepo.getDefault(account.id.get) } match {
          case Some(pm) => {
            val pendingChargeAccount = db.readWrite { implicit session =>
              paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Pending))
            }

            stripeClient.processCharge(amount, pm.stripeToken, s"Charging organization ${account.orgId} owing ${account.owed}") andThen {
              case Failure(ex) =>
                log.error(s"[PPC][${account.orgId}] Unexpected exception while processing charge via Stripe.", ex)
                db.readWrite(attempts = 3) { implicit session =>
                  paidAccountRepo.save(pendingChargeAccount.withPaymentStatus(PaymentStatus.Required))
                }
            } map {
              case success: StripeChargeSuccess => endWithChargeSuccess(pendingChargeAccount, pm.id.get, success)
              case failure: StripeChargeFailure => endWithChargeFailure(pendingChargeAccount, pm.id.get, amount, failure)
            }
          }
          case None => Future.successful(endWithMissingPaymentMethod(account))
        }
      }

      case PaymentStatus.Failed | PaymentStatus.Pending => {
        val error = new IllegalStateException(s"Attempt to charge account ${account.id.get} of org ${account.orgId} with invalid status: ${account.paymentStatus}.")
        log.error(s"[PPC][${account.orgId}] Aborting charge.", error)
        if (account.paymentStatus == PaymentStatus.Pending) airbrake.notify(error)
        Future.successful((account, None))
      }
    }
  }

  private def endWithChargeSuccess(account: PaidAccount, paymentMethodId: Id[PaymentMethod], success: StripeChargeSuccess): (PaidAccount, Some[AccountEvent]) = {
    db.readWrite(attempts = 3) { implicit session =>
      val chargedAccount = paidAccountRepo.save(account.withIncreasedCredit(success.amount).withPaymentStatus(PaymentStatus.Ok))
      val chargeEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        billingRelated = true,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.Charge(),
        creditChange = chargedAccount.credit - account.credit,
        paymentMethod = Some(paymentMethodId),
        paymentCharge = Some(success.amount),
        memo = None,
        chargeId = Some(success.chargeId)
      ))
      log.info(s"[PPC][${account.orgId}] Processed charge for amount ${success.amount}")
      (chargedAccount, Some(chargeEvent))
    }
  }

  private def endWithChargeFailure(account: PaidAccount, paymentMethodId: Id[PaymentMethod], amount: DollarAmount, failure: StripeChargeFailure): (PaidAccount, Some[AccountEvent]) = {
    db.readWrite(attempts = 3) { implicit session =>
      val chargeFailedAccount: PaidAccount = paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Failed))
      val chargeFailureEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        billingRelated = true,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.ChargeFailure(amount, failure.code, failure.message),
        creditChange = DollarAmount.ZERO,
        paymentMethod = Some(paymentMethodId),
        paymentCharge = None,
        memo = None,
        chargeId = None
      ))
      log.info(s"[PPC][${account.orgId}] Failed to charge via Stripe: ${failure.code}, ${failure.message}")
      (chargeFailedAccount, Some(chargeFailureEvent))
    }
  }

  private def endWithMissingPaymentMethod(account: PaidAccount): (PaidAccount, Some[AccountEvent]) = {
    db.readWrite { implicit session =>
      val chargeFailedAccount: PaidAccount = paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Failed))
      val missingPaymentMethodEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        billingRelated = true,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.MissingPaymentMethod(),
        creditChange = DollarAmount.ZERO,
        paymentMethod = None,
        paymentCharge = None,
        memo = None,
        chargeId = None
      ))
      log.error(s"[PPC][${account.orgId}] Missing default payment method!")
      (chargeFailedAccount, Some(missingPaymentMethodEvent))
    }
  }

  def forceChargeAccount(orgId: Id[Organization], amount: DollarAmount): Future[Option[AccountEvent]] = {
    accountLockHelper.maybeWithAccountLockAsync(orgId) {
      val account = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(orgId) }
      chargeAccount(account, amount).imap { case (_, chargeEvent) => chargeEvent }
    } getOrElse { throw new LockedAccountException(orgId) }
  }
}
