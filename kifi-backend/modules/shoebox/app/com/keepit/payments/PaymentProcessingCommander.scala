package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ Organization }
import com.keepit.common.concurrent.{ ReactiveLock }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient }
import com.keepit.common.core._

import com.google.inject.{ ImplementedBy, Inject, Singleton }

import play.api.libs.json.{ JsNull }

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[PaymentProcessingCommanderImpl])
trait PaymentProcessingCommander {
  def processAllBilling(): Future[Unit]
  def forceChargeAccount(orgId: Id[Organization], amount: DollarAmount): Future[AccountEvent] //not private for admin use
  def processAccount(account: PaidAccount): Future[Seq[AccountEvent]]

  private[payments] val MIN_BALANCE: DollarAmount
  private[payments] val MAX_BALANCE: DollarAmount

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
  eventCommander: AccountEventTrackingCommander,
  implicit val defaultContext: ExecutionContext)
    extends PaymentProcessingCommander with Logging {

  private[payments] val MAX_BALANCE = DollarAmount.wholeDollars(1000) //if you owe us more than $100 we will charge your card even if your billing cycle is not up
  private[payments] val MIN_BALANCE = DollarAmount.wholeDollars(1) //if you are carrying a balance of less then one dollar you will not be charged (to much cost overhead)

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
        val shouldProcess = (billingCycleElapsed || maxBalanceExceeded)
        if (shouldProcess) {
          val (billedAccount, billingEvent) = if (billingCycleElapsed) billAccount(account, plan) else (account, None)
          if (billedAccount.owed > MIN_BALANCE) {
            val chargeAction = if (billingCycleElapsed) AccountEventAction.PlanBillingCharge() else AccountEventAction.MaxBalanceExceededCharge()
            chargeAccount(billedAccount, billedAccount.owed, chargeAction).map {
              case (maybeChargedAccount, chargeEvent) =>
                billingEvent.toSeq :+ chargeEvent
            }
          } else {
            val lowBalanceIgnoredEvent = ignoreLowBalance(billedAccount)
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
      val billingEvent = accountEventRepo.save(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        billingRelated = true,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = None,
        action = AccountEventAction.PlanBilling(),
        creditChange = billedAccount.credit - account.credit,
        paymentMethod = None,
        paymentCharge = None,
        memo = None,
        chargeId = None
      ))
      (billedAccount, Some(billingEvent))
    }
  }

  private def ignoreLowBalance(account: PaidAccount): AccountEvent = {
    db.readWrite { implicit session =>
      accountEventRepo.save(AccountEvent(
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
    }
  }

  private def chargeAccount(account: PaidAccount, amount: DollarAmount, chargeAction: AccountEventAction): Future[(PaidAccount, AccountEvent)] = {
    db.readOnlyMaster { implicit session => paymentMethodRepo.getDefault(account.id.get) } match {
      case Some(pm) => {
        val description = chargeAction match {
          case AccountEventAction.PlanBillingCharge() => s"Regular charge for org ${account.orgId} of amount $amount"
          case AccountEventAction.MaxBalanceExceededCharge() => s"Max balance exceeded charge for org ${account.orgId} of amount $amount"
          case AccountEventAction.ForcedCharge() => s"Forced charge for org ${account.orgId} of $amount"
        }
        stripeClient.processCharge(amount, pm.stripeToken, description).map {
          case StripeChargeSuccess(amount, chargeId) => {
            db.readWrite { implicit session =>
              val chargedAccount = paidAccountRepo.save(account.withIncreasedCredit(amount))
              val chargeEvent = accountEventRepo.save(AccountEvent(
                eventTime = clock.now(),
                accountId = account.id.get,
                billingRelated = true,
                whoDunnit = None,
                whoDunnitExtra = JsNull,
                kifiAdminInvolved = None,
                action = chargeAction,
                creditChange = chargedAccount.credit - account.credit,
                paymentMethod = pm.id,
                paymentCharge = Some(amount),
                memo = None,
                chargeId = Some(chargeId)
              ))
              log.info(s"[PPC][${account.orgId}] Processed charge for amount $amount: $description")
              (chargedAccount, chargeEvent)
            }
          }
          case failure @ StripeChargeFailure(code, message) => {
            db.readWrite { implicit session =>
              val updatedAccount: PaidAccount = account // todo(Léo): flag with potential actionRequired
              val chargeFailureEvent = accountEventRepo.save(AccountEvent(
                eventTime = clock.now(),
                accountId = account.id.get,
                billingRelated = true,
                whoDunnit = None,
                whoDunnitExtra = JsNull,
                kifiAdminInvolved = None,
                action = AccountEventAction.ChargeFailure(amount, failure),
                creditChange = DollarAmount.ZERO,
                paymentMethod = pm.id,
                paymentCharge = None,
                memo = None,
                chargeId = None
              ))
              log.info(s"[PPC][${account.orgId}] Failed to charge via Stripe: $code, $message")
              (updatedAccount, chargeFailureEvent)
            }
          }
        }
      }
      case None => {
        db.readWrite { implicit session =>
          val updatedAccount: PaidAccount = account // todo(Léo): flag with potential actionRequired
          val missingPaymentMethodEvent = accountEventRepo.save(AccountEvent(
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
          log.info(s"[PPC][${account.orgId}] Missing default payment method!")
          Future.successful((updatedAccount, missingPaymentMethodEvent))
        }
      }
    }
  }

  def forceChargeAccount(orgId: Id[Organization], amount: DollarAmount): Future[AccountEvent] = {
    accountLockHelper.maybeWithAccountLockAsync(orgId) {
      val account = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(orgId) }
      chargeAccount(account, amount, AccountEventAction.ForcedCharge()).imap { case (_, chargeEvent) => chargeEvent }
    } getOrElse { throw new LockedAccountException(orgId) }
  }
}
