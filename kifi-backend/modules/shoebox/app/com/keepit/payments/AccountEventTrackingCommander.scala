package com.keepit.payments

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.commanders.{ BasicSlackMessage, PathCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail }
import com.keepit.common.net.{ HttpClient, DirectUrl }
import com.keepit.model.{ Organization, OrganizationRepo, UserEmailAddressRepo, NotificationCategory }
import play.api.Mode
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import com.keepit.common.core._

@ImplementedBy(classOf[AccountEventTrackingCommanderImpl])
trait AccountEventTrackingCommander {
  def track(event: AccountEvent)(implicit session: RWSession): AccountEvent
  def reportToSlack(msg: String, channel: String): Future[Unit] // todo(Léo): *temporary* :)
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
    airbrake: AirbrakeNotifier,
    activityCommander: ActivityLogCommander,
    implicit val defaultContext: ExecutionContext) extends AccountEventTrackingCommander {

  def track(event: AccountEvent)(implicit session: RWSession): AccountEvent = {
    eventRepo.save(event) tap { savedEvent =>
      session.onTransactionSuccess { report(savedEvent) }
    }
  }

  private def report(event: AccountEvent): Future[Unit] = if (mode == play.api.Mode.Prod) {
    val (a, o, m): (PaidAccount, Organization, Option[PaymentMethod]) = db.readOnlyMaster { implicit session =>
      val a = accountRepo.get(event.accountId)
      val o = orgRepo.get(a.orgId)
      val m = event.paymentMethod.map(paymentMethodRepo.get)
      (a, o, m)
    }

    implicit val account = a
    implicit val org = o
    implicit val paymentMethod = m

    Future.sequence(Seq(notifyOfCharge(event).imap(_ => ()), notifyOfError(event).imap(_ => ()), reportToSlack(event).imap(_ => ()))).imap(_ => ())
  } else Future.successful(())

  // todo(Léo): *temporary* this was copied straight from PaymentProcessingCommander
  private val slackChannelUrl = "https://hooks.slack.com/services/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"
  private val reportingLock = new ReactiveLock(1) // guarantees event reporting order
  def reportToSlack(msg: String, channel: String): Future[Unit] = reportingLock.withLockFuture {
    SafeFuture {
      if (msg.nonEmpty && mode == play.api.Mode.Prod) {
        val fullMsg = BasicSlackMessage(
          text = if (mode == Mode.Prod) msg else "[TEST]" + msg,
          username = "Activity",
          channel = Some(channel)
        )
        httpClient.post(DirectUrl(slackChannelUrl), Json.toJson(fullMsg))
      } else {
        Future.successful(())
      }
    }
  }

  private def reportToSlack(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Seq[String]] = {
    checkingParameters(event) {
      lazy val msg = {
        val info = activityCommander.buildSimpleEventInfo(event)
        val description = info.description.flatten.map {
          case BasicElement(text, None) => text
          case BasicElement(text, Some(url)) => s"<$url|$text>"
        } mkString (" ")
        val orgHeader = s"<https://admin.kifi.com/admin/organization/${org.id.get}|${org.name}>"
        s"[$orgHeader] $description | ${info.creditChange}"
      }
      Future.sequence(toSlackChannels(event.action.eventType).map { channel =>
        reportToSlack(msg, channel).imap(_ => channel)
      })
    }
  }

  private def toSlackChannels(eventType: AccountEventKind): Seq[String] = {
    import AccountEventKind._
    eventType match {
      case kind if billing.contains(kind) => Seq("#billing-alerts")
      case OrganizationCreated | UserJoinedOrganization | UserLeftOrganization | OrganizationRoleChanged => Seq("#org-members")
    }
  }

  private def notifyOfError(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Boolean] = {
    checkingParameters(event) {
      if (event.action.eventType == AccountEventKind.IntegrityError) {
        airbrake.notify(s"Account ${event.accountId} has an integrity error: ${event.action}")
        Future.successful(true)
      } else Future.successful(false)
    }
  }

  private def notifyOfCharge(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Boolean] = {
    checkingParameters(event) {
      event.chargeId match {
        case Some(chargeId) => notifyOfCharge(account, paymentMethod.get.stripeToken, event.paymentCharge.get, chargeId).imap(_ => true)
        case None => Future.successful(false)
      }
    }
  }

  private def notifyOfCharge(account: PaidAccount, stripeToken: StripeToken, amount: DollarAmount, chargeId: String): Future[Unit] = {
    val lastFourFuture = stripeClient.getLastFourDigitsOfCard(stripeToken)
    val (userContacts, org) = db.readOnlyReplica { implicit session =>
      val userContacts = account.userContacts.flatMap { userId =>
        Try(emailRepo.getByUser(userId)).toOption
      }
      (userContacts, orgRepo.get(account.orgId))
    }
    val emails = (account.emailContacts ++ userContacts).distinct

    lastFourFuture.map { lastFour =>
      val subject = s"We've charged you card for your Kifi Organization ${org.name}"
      val htmlBody = s"""|<p>You card on file ending in $lastFour has been charged $amount (ref. $chargeId).<br/>
      |For more details please consult <a href="${pathCommander.pathForOrganization(org).absolute}/settings/activity">your account history<a>.</p>
      |
      |<p>Thanks,
      |The Kifi Team</p>
      """.stripMargin
      val textBody = s"""|You card on file ending in $lastFour has been charged $amount (ref. $chargeId).
      |For more details please consult your account history at ${pathCommander.pathForOrganization(org).absolute}/settings/activity.
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

  private def checkingParameters[T](event: AccountEvent)(f: => Future[T])(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[T] = {
    val futureMaybe = if (!account.id.contains(event.accountId)) Failure(new IllegalArgumentException(s"Event ${event.id.get} does not belong to account ${account.id.get}!"))
    else if (!org.id.contains(account.orgId)) Failure(new IllegalArgumentException(s"Account ${account.id.get} does not belong to organization ${org.id.get}!"))
    else if (paymentMethod.exists(_.accountId != account.id.get)) Failure(new IllegalArgumentException(s"PaymentMethod ${paymentMethod.flatMap(_.id).get} does not belong to account ${account.id.get}!"))
    else if (event.paymentMethod != paymentMethod.flatMap(_.id)) Failure(new IllegalArgumentException(s"PaymentMethod ${paymentMethod.flatMap(_.id).getOrElse("None")} does not match event ${event.id.get}!"))
    else Try(f)
    futureMaybe recover { case error => Future.failed(error) } get
  }
}
