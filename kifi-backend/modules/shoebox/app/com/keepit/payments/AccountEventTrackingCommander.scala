package com.keepit.payments

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.commanders.{ BasicSlackMessage, PathCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail }
import com.keepit.common.net.{ HttpClient, DirectUrl }
import com.keepit.model.{ OrganizationRepo, UserEmailAddressRepo, NotificationCategory }
import play.api.Mode
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import com.keepit.common.core._

@ImplementedBy(classOf[AccountEventTrackingCommanderImpl])
trait AccountEventTrackingCommander {
  def track(event: AccountEvent)(implicit session: RWSession): AccountEvent
  def reportToSlack(msg: String): Future[Unit] // todo(Léo): *temporary* :)
}

@Singleton
class AccountEventTrackingCommanderImpl @Inject() (
    db: Database,
    emailRepo: UserEmailAddressRepo,
    orgRepo: OrganizationRepo,
    eventRepo: AccountEventRepo,
    accountRepo: PaidAccountRepo,
    paymentMethodRepo: PaymentMethodRepo,
    pathCommander: PathCommander,
    postOffice: LocalPostOffice,
    stripeClient: StripeClient,
    httpClient: HttpClient,
    mode: play.api.Mode.Mode,
    implicit val defaultContext: ExecutionContext) extends AccountEventTrackingCommander {

  def track(event: AccountEvent)(implicit session: RWSession): AccountEvent = {
    eventRepo.save(event) tap { savedEvent =>
      session.onTransactionSuccess { report(savedEvent) }
    }
  }

  private def report(event: AccountEvent): Unit = {
    if (AccountEventKind.billing.contains(event.action.eventType)) {
      val (account, org, paymentMethod) = db.readOnlyMaster { implicit session =>
        val account = accountRepo.get(event.accountId)
        val org = orgRepo.get(account.orgId)
        val paymentMethod = event.paymentMethod.map(paymentMethodRepo.get)
        (account, org, paymentMethod)
      }
      reportToSlack(s"[<https://admin.kifi.com/admin/organization/${org.id.get}|${org.name}>][Payment: ${account.paymentStatus.value}][${event.action.eventType}] => Credit: ${event.creditChange.toDollarString} | Charge: ${event.paymentCharge.getOrElse(DollarAmount.ZERO).toDollarString} [Event #${event.id.get}]")

      // todo(Léo): not sure this one belongs here vs PaymentProcessingCommander
      event.chargeId.foreach { chargeId =>
        notifyOfCharge(account, paymentMethod.get.stripeToken, event.paymentCharge.get, chargeId)
      }
    }
  }

  // todo(Léo): *temporary* this was copied straight from PaymentProcessingCommander

  private val slackChannelUrl = "https://hooks.slack.com/services/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"
  def reportToSlack(msg: String): Future[Unit] = SafeFuture {
    if (msg.nonEmpty) {
      val fullMsg = BasicSlackMessage(
        text = if (mode == Mode.Prod) msg else "[TEST]" + msg,
        username = "AccountEvent",
        channel = Some("#billing-alerts")
      )
      httpClient.post(DirectUrl(slackChannelUrl), Json.toJson(fullMsg))
    } else {
      Future.successful(())
    }
  }

  private def notifyOfCharge(account: PaidAccount, stripeToken: StripeToken, amount: DollarAmount, chargeId: String): Unit = {
    val lastFourFuture = stripeClient.getLastFourDigitsOfCard(stripeToken)
    val (userContacts, org) = db.readOnlyReplica { implicit session =>
      val userContacts = account.userContacts.flatMap { userId =>
        Try(emailRepo.getByUser(userId)).toOption
      }
      (userContacts, orgRepo.get(account.orgId))
    }
    val emails = (account.emailContacts ++ userContacts).distinct

    val handle = org.handle

    lastFourFuture.map { lastFour =>
      val subject = s"We've charged you card for your Kifi Organization ${org.name}"
      val htmlBody = s"""|<p>You card on file ending in $lastFour has been charged $amount (ref. $chargeId).<br/>
      |For more details please consult your account history at <a href="${pathCommander.pathForOrganization(org).absolute}/settings">www.kifi.com/${handle.value}/settings<a>.</p>
      |
      |<p>Thanks,
      |The Kifi Team</p>
      """.stripMargin
      val textBody = s"""|You card on file ending in $lastFour has been charged $amount (ref. $chargeId).
      |For more details please consult your account history at ${pathCommander.pathForOrganization(org).absolute}/settings.
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
}
