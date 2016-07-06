package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.common.util.{DescriptionElements, DollarAmount}
import com.keepit.model._
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.core._

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.slack.{InhouseSlackChannel, InhouseSlackClient}
import com.keepit.slack.models.{SlackMessageRequest, SlackChannelName}
import org.joda.time.DateTime

import play.api.libs.json.{ JsNull }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

case class PaymentCycle(months: Int) extends AnyVal
object PaymentCycle {
  def months(n: Int): PaymentCycle = PaymentCycle(n)
}

@ImplementedBy(classOf[PaymentProcessingCommanderImpl])
trait PaymentProcessingCommander {
  def processDuePayments(): Future[Unit]
  def processAccount(orgId: Id[Organization]): Future[(PaidAccount, AccountEvent)]
  def processAccount(account: PaidAccount): Future[(PaidAccount, AccountEvent)]
  def forceChargeAccount(orgId: Id[Organization], amount: DollarAmount): Future[(PaidAccount, AccountEvent)]
  def refundCharge(eventId: Id[AccountEvent], grantedByAdmin: Id[User]): Future[(PaidAccount, AccountEvent)]

  private[payments] val MIN_BALANCE: DollarAmount
  private[payments] val MAX_BALANCE: DollarAmount
  private[payments] val PAYMENT_CYCLE: PaymentCycle

}

@Singleton
class PaymentProcessingCommanderImpl @Inject() (
  db: Database,
  paymentMethodRepo: PaymentMethodRepo,
  paidAccountRepo: PaidAccountRepo,
  accountEventRepo: AccountEventRepo,
  clock: Clock,
  accountLockHelper: AccountLockHelper,
  stripeClient: StripeClient,
  airbrake: AirbrakeNotifier,
  eventCommander: AccountEventTrackingCommander,
  orgExperimentRepo: OrganizationExperimentRepo,
  implicit val defaultContext: ExecutionContext,
  inhouseSlackClient: InhouseSlackClient)
    extends PaymentProcessingCommander with Logging {

  private[payments] val MAX_BALANCE = DollarAmount.dollars(500)
  //if you owe us more than $100 we will charge your card even if your billing cycle is not up
  private[payments] val MIN_BALANCE = DollarAmount.dollars(1)
  //if you are carrying a balance of less then one dollar you will not be charged (to much cost overhead)
  private[payments] val PAYMENT_CYCLE = PaymentCycle.months(1) //how often oustanding balances will be charged

  private def nextPaymentDueAt(account: PaidAccount): Option[DateTime] = {
    val strictlyDueAt = clock.now() plusMonths PAYMENT_CYCLE.months
    val looselyDueAt = strictlyDueAt plusDays 1
    if (account.planRenewal isBefore looselyDueAt) None else Some(strictlyDueAt) // do not schedule a payment just before plan renewal
  }

  private val processingLock = new ReactiveLock(1)

  def processDuePayments(): Future[Unit] = processingLock.withLockFuture {
//    val relevantAccounts = Seq.empty//db.readOnlyMaster { implicit session => paidAccountRepo.getPayable(MAX_BALANCE) }
//    if (relevantAccounts.nonEmpty) {
//      inhouseSlackClient.sendToSlack(InhouseSlackChannel.BILLING_ALERTS, SlackMessageRequest.inhouse(DescriptionElements("Processing payments for", relevantAccounts.length, "accounts.")))
//      FutureHelpers.foldLeft(relevantAccounts)(0) {
//        case (processed, account) =>
//          processAccount(account).imap(_ => processed + 1) recover {
//            case e: Exception => {
//              val message = s"Fatal error processing organization ${account.orgId}"
//              log.error(message, e)
//              airbrake.notify(message, e)
//              processed
//            }
//          }
//      } imap { processed =>
//        inhouseSlackClient.sendToSlack(InhouseSlackChannel.BILLING_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(s"Processed $processed/${relevantAccounts.length} due payments.")))
//      }
//    } else
    Future.successful(())
  }

  def processAccount(orgId: Id[Organization]): Future[(PaidAccount, AccountEvent)] = {
    val account = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(orgId) }
    processAccount(account)
  }

  def processAccount(account: PaidAccount): Future[(PaidAccount, AccountEvent)] = {
    accountLockHelper.maybeWithAccountLockAsync(account.orgId) {
      log.info(s"[PPC][${account.orgId}] Starting Processing")
      if (!account.frozen) {
        if (account.owed > MIN_BALANCE) {
          //chargeAccount(account, account.owed)
          Future.successful(ignoreLowBalance(account))
        } else {
          Future.successful(ignoreLowBalance(account))
        }
      } else Future.failed(FrozenAccountException(account.orgId))
    } getOrElse Future.failed(LockedAccountException(account.orgId))
  }

  def forceChargeAccount(orgId: Id[Organization], amount: DollarAmount): Future[(PaidAccount, AccountEvent)] = {
    accountLockHelper.maybeWithAccountLockAsync(orgId) {
      val account = db.readOnlyMaster { implicit session => paidAccountRepo.getByOrgId(orgId) }
      if (!account.frozen) {
        //chargeAccount(account, amount)
        Future.failed(FrozenAccountException(orgId))
      } else {
        Future.failed(FrozenAccountException(orgId))
      }
    } getOrElse Future.failed(LockedAccountException(orgId))
  }

  private def ignoreLowBalance(account: PaidAccount): (PaidAccount, AccountEvent) = {
    db.readWrite { implicit session =>
      val updatedAccount = paidAccountRepo.save(account.withPaymentDueAt(nextPaymentDueAt(account)))
      val lowBalanceIgnoredEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
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

  private def withPendingStatus[T <: StripeTransactionResult](account: PaidAccount)(stripeCall: => Future[T]): Future[(PaidAccount, T)] = {
    val pendingAccount = db.readWrite { implicit session =>
      paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Pending))
    }
    stripeCall.imap((pendingAccount, _)) andThen {
      case Failure(ex) =>
        log.error(s"[PPC][${account.orgId}] Unexpected exception while calling Stripe.", ex)
        db.readWrite(attempts = 3) {
          implicit session =>
            paidAccountRepo.save(pendingAccount.withPaymentStatus(account.paymentStatus))
        }
    }
  }

  private val stripeLock = new ReactiveLock(1)

  private def chargeAccount(account: PaidAccount, amount: DollarAmount): Future[(PaidAccount, AccountEvent)] = stripeLock.withLockFuture {
    lazy val isFakeAccount = db.readOnlyMaster { implicit session => orgExperimentRepo.hasExperiment(account.orgId, OrganizationExperimentType.FAKE) }

    account.paymentStatus match {
      case PaymentStatus.Ok => {
        db.readOnlyMaster { implicit session => paymentMethodRepo.getDefault(account.id.get) } match {
          case Some(pm) => {
            withPendingStatus(account) {
              if (isFakeAccount) Future.successful(StripeChargeSuccess(account.owed, StripeTransactionId("ch_fake")))
              else stripeClient.processCharge(amount, pm.stripeToken, s"Charging organization ${account.orgId} owing ${account.owed}")
            } map {
              case (pendingAccount, success: StripeChargeSuccess) => endWithChargeSuccess(pendingAccount, pm.id.get, success)
              case (pendingAccount, failure: StripeChargeFailure) => endWithChargeFailure(pendingAccount, pm.id.get, amount, failure)
            }
          }
          case None => Future.successful(endWithMissingPaymentMethod(account))
        }
      }

      case PaymentStatus.Failed | PaymentStatus.Pending => {
        val error = new IllegalStateException(s"Attempt to charge account ${account.id.get} of org ${account.orgId} with invalid payment status: ${account.paymentStatus}.")
        log.error(s"[PPC][${account.orgId}] Aborting charge.", error)
        if (account.paymentStatus == PaymentStatus.Pending) airbrake.notify(error)
        Future.failed(InvalidPaymentStatusException(account.orgId, account.paymentStatus))
      }
    }
  }

  private def endWithChargeSuccess(account: PaidAccount, paymentMethodId: Id[PaymentMethod], success: StripeChargeSuccess): (PaidAccount, AccountEvent) = {
    db.readWrite(attempts = 3) { implicit session =>
      val chargedAccount = paidAccountRepo.save(account.withIncreasedCredit(success.amount).withPaymentStatus(PaymentStatus.Ok).withPaymentDueAt(nextPaymentDueAt(account)))
      val chargeEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
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
      (chargedAccount, chargeEvent)
    }
  }

  private def endWithChargeFailure(account: PaidAccount, paymentMethodId: Id[PaymentMethod], amount: DollarAmount, failure: StripeChargeFailure): (PaidAccount, AccountEvent) = {
    db.readWrite(attempts = 3) { implicit session =>
      val chargeFailedAccount: PaidAccount = paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Failed))
      val chargeFailureEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
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
      (chargeFailedAccount, chargeFailureEvent)
    }
  }

  private def endWithMissingPaymentMethod(account: PaidAccount): (PaidAccount, AccountEvent) = {
    db.readWrite { implicit session =>
      val chargeFailedAccount: PaidAccount = paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Failed))
      val missingPaymentMethodEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
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
      (chargeFailedAccount, missingPaymentMethodEvent)
    }
  }

  def refundCharge(eventId: Id[AccountEvent], grantedByAdmin: Id[User]): Future[(PaidAccount, AccountEvent)] = stripeLock.withLockFuture {
    val (event, account) = db.readOnlyMaster { implicit session =>
      val event = accountEventRepo.get(eventId)
      val account = paidAccountRepo.get(event.accountId)
      (event, account)
    }

    (event.chargeId, event.paymentCharge, event.paymentMethod) match {
      case (Some(chargeId), Some(amount), Some(paymentMethod)) if amount > DollarAmount.ZERO =>
        withPendingStatus(account) {
          stripeClient.refundCharge(chargeId)
        } map {
          case (pendingAccount, success: StripeRefundSuccess) => endWithRefundSuccess(pendingAccount, paymentMethod, eventId, chargeId, grantedByAdmin, success)
          case (pendingAccount, failure: StripeRefundFailure) => endWithRefundFailure(pendingAccount, paymentMethod, eventId, chargeId, grantedByAdmin, failure)
        }
      case _ => Future.failed(new IllegalArgumentException(s"Invalid event $eventId, unable to refund charge: $event"))
    }
  }

  private def endWithRefundSuccess(account: PaidAccount, paymentMethodId: Id[PaymentMethod], originalChargeEvent: Id[AccountEvent], originalCharge: StripeTransactionId, grantedByAdmin: Id[User], success: StripeRefundSuccess): (PaidAccount, AccountEvent) = {
    db.readWrite(attempts = 3) { implicit session =>
      val refundedAccount = paidAccountRepo.save(account.withReducedCredit(success.amount).withPaymentStatus(PaymentStatus.Ok))
      val refundEvent = eventCommander.track(AccountEvent(
        eventTime = clock.now(),
        accountId = account.id.get,
        whoDunnit = None,
        whoDunnitExtra = JsNull,
        kifiAdminInvolved = Some(grantedByAdmin),
        action = AccountEventAction.Refund(originalChargeEvent, originalCharge),
        creditChange = refundedAccount.credit - account.credit,
        paymentMethod = Some(paymentMethodId),
        paymentCharge = Some(-success.amount), // a refund is just treated as a negative charge
        memo = None,
        chargeId = Some(success.refundId)
      ))
      log.info(s"[PPC][${account.orgId}] Refunded ${success.amount} via Stripe")
      (refundedAccount, refundEvent)
    }
  }

  private def endWithRefundFailure(account: PaidAccount, paymentMethodId: Id[PaymentMethod], originalChargeEvent: Id[AccountEvent], originalCharge: StripeTransactionId, grantedByAdmin: Id[User], failure: StripeRefundFailure): (PaidAccount, AccountEvent) = {
      db.readWrite(attempts = 3) { implicit session =>
        val refundFailedAccount: PaidAccount = paidAccountRepo.save(account.withPaymentStatus(PaymentStatus.Failed))
        val refundFailureEvent = eventCommander.track(AccountEvent(
          eventTime = clock.now(),
          accountId = account.id.get,
          whoDunnit = None,
          whoDunnitExtra = JsNull,
          kifiAdminInvolved = Some(grantedByAdmin),
          action = AccountEventAction.RefundFailure(originalChargeEvent, originalCharge, failure.code, failure.message),
          creditChange = DollarAmount.ZERO,
          paymentMethod = Some(paymentMethodId),
          paymentCharge = None,
          memo = None,
          chargeId = None
        ))
        log.info(s"[PPC][${account.orgId}] Failed to refund charge via Stripe: ${failure.code}, ${failure.message}")
        (refundFailedAccount, refundFailureEvent)
      }
    }
}
