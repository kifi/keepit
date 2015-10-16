package com.keepit.payments

import java.net.URLEncoder

import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ Organization, NotificationCategory, UserEmailAddressRepo, OrganizationRepo }
import com.keepit.common.concurrent.{ ReactiveLock, FutureHelpers }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail, EmailAddress }
import com.keepit.commanders.{ PathCommander, BasicSlackMessage }
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.akka.SafeFuture

import com.google.inject.{ ImplementedBy, Inject, Singleton }

import play.api.libs.json.{ JsNull, Json }
import play.api.Mode
import play.api.Mode.Mode

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Success, Failure }

@ImplementedBy(classOf[PaymentProcessingCommanderImpl])
trait PaymentProcessingCommander {
  def processAllBilling(): Future[Unit]
  def forceChargeAccount(account: PaidAccount, amount: DollarAmount, description: Option[String]): Future[StripeChargeResult] //not private for admin use
  def processAccount(account: PaidAccount): Future[BillingResult]

  private[payments] val MIN_BALANCE: DollarAmount
  private[payments] val MAX_BALANCE: DollarAmount

}

case object MissingPaymentMethodException extends Exception("missing_default_payment_method")

case class BillingResult(amount: DollarAmount, reason: BillingResultReason, stripeResult: Option[StripeChargeResult])


trait BillingResultReason {
  val message: String
}

object BillingResultReason {
  case object LOW_BALANCE extends BillingResultReason {
    val message = "Not charging because of low balance."
  }
  case object NO_LOCK extends BillingResultReason {
    val message = "Failed to get lock."
  }
  case object MAX_BALANCE_EXCEEDED extends BillingResultReason {
    val message = "Max balance exceeded."
  }
  case object BILLING_CYCLE_ELAPSED extends BillingResultReason {
    val message = "Billing Cycle elapsed."
  }
  case object STRIPE_FAILURE extends BillingResultReason {
    val message = s"Stripe Call Failed."
  }
  case class CONDITIONS_NOT_MET(frozen: Boolean) extends BillingResultReason {
    val message = s"Not processed because conditions not met. Frozen: ${frozen}."
  }
  case object MISSING_PAYMENT_METHOD extends BillingResultReason {
    val message = "No default payment method available."
  }
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
  pathCommander: PathCommander,
  postOffice: LocalPostOffice,
  emailRepo: UserEmailAddressRepo,
  orgRepo: OrganizationRepo,
  httpClient: HttpClient,
  mode: Mode,
  implicit val defaultContext: ExecutionContext)
    extends PaymentProcessingCommander with Logging {

  private[payments] val MAX_BALANCE = DollarAmount.wholeDollars(-1000) //if you owe us more than $100 we will charge your card even if your billing cycle is not up
  private[payments] val MIN_BALANCE = DollarAmount.wholeDollars(-1) //if you are carrying a balance of less then one dollar you will not be charged (to much cost overhead)

  private val slackChannelUrl = "https://hooks.slack.com/services/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"

  private val processingLock = new ReactiveLock(2)

  private def notifyOfCharge(account: PaidAccount, stripeToken: StripeToken, amount: DollarAmount, chargeId: String): Unit = {
    val lastFourFuture = stripeClient.getLastFourDigitsOfCard(stripeToken)
    val (userContacts, org) = db.readOnlyReplica { implicit session =>
      val userContacts = account.userContacts.flatMap { userId =>
        Try(emailRepo.getByUser(userId)).toOption
      }
      (userContacts, orgRepo.get(account.orgId))
    }
    val emails = (account.emailContacts ++ userContacts).distinct

    val path = pathCommander.pathForOrganization(org).absolute + "/settings"

    lastFourFuture.map { lastFour =>
      val subject = s"We've charged you card for your Kifi Organization ${org.name}"
      val htmlBody = s"""|<p>You card on file ending in $lastFour has been charged $amount (ref. $chargeId).<br/>
      |For more details please consult your account history at <a href="$path">$path<a>.</p>
      |
      |<p>Thanks,
      |The Kifi Team</p>
      """.stripMargin
      val textBody = s"""|You card on file ending in $lastFour has been charged $amount (ref. $chargeId).
      |For more details please consult your account history at $path.
      |
      |Thanks, <br/>
      |The Kifi Team
      """.stripMargin
      db.readWrite { implicit session =>
        postOffice.sendMail(ElectronicMail(
          from = SystemEmailAddress.BILLING,
          fromName = Some("Kifi Billing"),
          to = emails,
          subject = subject,
          htmlBody = htmlBody,
          textBody = Some(textBody),
          category = NotificationCategory.NonUser.BILLING
        ))
      }
    }
  }

  private def reportToSlack(msg: String): Future[Unit] = SafeFuture {
    if (msg != "") {
      val fullMsg = BasicSlackMessage(
        text = if (mode == Mode.Prod) msg else "[TEST]" + msg,
        username = "PaymentProcessingCommander",
        channel = Some("#billing-alerts")
      )
      httpClient.post(DirectUrl(slackChannelUrl), Json.toJson(fullMsg))
    } else {
      Future.successful(())
    }
  }

  def processAllBilling(): Future[Unit] = processingLock.withLockFuture {
    val relevantAccounts = db.readOnlyMaster { implicit session => paidAccountRepo.getRipeAccounts(maxBalance = MAX_BALANCE, maxCycleAge = clock.now.minusMonths(1)) } //we check at least monthly, even for accounts on longer billing cycles + accounts with large balance
    if (relevantAccounts.length > 0) reportToSlack(s"Processing Payments. ${relevantAccounts.length} orgs to check.")
    val resultsFuture = Future.sequence(relevantAccounts.map { account =>
      processAccount(account).map { result =>
        account.orgId -> Success(result)
      }.recover {
        case t: Throwable => account.orgId -> Failure(t)
      }
    })
    resultsFuture.onComplete {
      case Success(results) => {
        reportToSlack(results.map {
          case (orgId, result) =>
            result match {
              case Success(BillingResult(amount, reason, stripeResultOpt)) => if (reason != BillingResultReason.LOW_BALANCE) {
                val org = db.readOnlyReplica { implicit s => orgRepo.get(orgId) }
                Some(s"""Processed Org <https://admin.kifi.com/admin/organization/id/$orgId|${URLEncoder.encode(org.name, "UTF-8")}>. Charged: $amount. Reason: $reason""")
              } else {
                None
              }
              case Failure(ex) => {
                log.error(s"Fatal Error processing Org $orgId. Reason: ${ex.getMessage}", ex)
                val org = db.readOnlyReplica { implicit s => orgRepo.get(orgId) }
                Some(s"""Fatal Error processing Org <https://admin.kifi.com/admin/organization/id/$orgId|${URLEncoder.encode(org.name, "UTF-8")}>. Reason: ${ex.getMessage}. See log for stack trace.""")
              }
            }
        }.flatten.mkString("\n"))
      }
      case Failure(ex) => reportToSlack(s"@channel Fatal Error during billing processing! $ex")
    }
    resultsFuture.map(_ => ())
  }

  def processAccount(account: PaidAccount): Future[BillingResult] = processingLock.withLockFuture {
    accountLockHelper.maybeWithAccountLockAsync(account.orgId) {
      log.info(s"[PPC][${account.orgId}] Starting Processing")
      db.readWrite { implicit session =>
        val plan = paidPlanRepo.get(account.planId)
        val billingCycleElapsed = account.billingCycleStart.plusMonths(plan.billingCycle.month).isBefore(clock.now)
        val maxBalanceExceeded = account.credit.cents < MAX_BALANCE.cents
        val shouldProcess = !account.frozen && (billingCycleElapsed || maxBalanceExceeded)
        if (shouldProcess) {
          val newBillingCycleStart = account.billingCycleStart.plusMonths(plan.billingCycle.month)
          val fullCyclePrice = DollarAmount(account.activeUsers * plan.pricePerCyclePerUser.cents)
          val updatedAccountPreCharge = if (billingCycleElapsed) account.withReducedCredit(fullCyclePrice).withCycleStart(newBillingCycleStart) else account

          if (account.credit.cents < MIN_BALANCE.cents) {
            val chargeAmount = DollarAmount(-1 * updatedAccountPreCharge.credit.cents)
            log.info(s"[PPC][${account.orgId}] Going to charge $chargeAmount")
            val description = if (maxBalanceExceeded) s"Max balance exceeded charge for org ${account.orgId} of amount $chargeAmount" else s"Regular charge for org ${account.orgId} of amount $chargeAmount"
            forceChargeAccount(updatedAccountPreCharge, chargeAmount, Some(description)).map { result =>
              result match {
                case result @ StripeChargeSuccess(amount, _) => {
                  BillingResult(
                    amount,
                    if (maxBalanceExceeded) BillingResultReason.MAX_BALANCE_EXCEEDED else BillingResultReason.BILLING_CYCLE_ELAPSED,
                    Some(result)
                  )
                }
                case result: StripeChargeFailure => BillingResult(DollarAmount.ZERO, BillingResultReason.STRIPE_FAILURE, Some(result))
              }
            }.recover {
              case MissingPaymentMethodException => {
                BillingResult(DollarAmount.ZERO, BillingResultReason.MISSING_PAYMENT_METHOD, None)
              }
            }
          } else {
            log.info(s"[PPC][${account.orgId}] Not Charging. Balance less than $MIN_BALANCE (${account.credit})")
            paidAccountRepo.save(updatedAccountPreCharge)
            Future.successful(BillingResult(DollarAmount.ZERO, BillingResultReason.LOW_BALANCE, None))
          }
        } else {
          Future.successful(BillingResult(DollarAmount.ZERO, BillingResultReason.CONDITIONS_NOT_MET(account.frozen), None))
        }
      }
    }.getOrElse(Future.successful(BillingResult(DollarAmount.ZERO, BillingResultReason.NO_LOCK, None)))
  }

  def forceChargeAccount(account: PaidAccount, amount: DollarAmount, descriptionOpt: Option[String]): Future[StripeChargeResult] = {
    db.readOnlyMaster { implicit session => paymentMethodRepo.getDefault(account.id.get) } match {
      case Some(pm) => {
        val description = descriptionOpt.getOrElse("Forced charge for org ${account.orgId} of $amount")
        stripeClient.processCharge(amount, pm.stripeToken, description).map {
          case result @ StripeChargeSuccess(amount, chargeId) => {
            db.readWrite { implicit session =>
              paidAccountRepo.save(account.withIncreasedCredit(amount))
              accountEventRepo.save(AccountEvent(
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
            }
            notifyOfCharge(account, pm.stripeToken, amount, chargeId)
            log.info(s"[PPC][${account.orgId}] Processed charge for amount $amount: $description")
            result
          }
          case result @ StripeChargeFailure(code, message) => {
            airbrake.notify(s"Stripe error charging org ${account.orgId}: $code, $message")
            result
          }
        }
      }
      case None => {
        airbrake.notify(s"Missing default payment method for org ${account.orgId}")
        Future.failed(MissingPaymentMethodException)
      }
    }
  }

}
