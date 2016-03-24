package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ ElectronicMail, EmailAddress, LocalPostOffice, SystemEmailAddress }
import com.keepit.common.time._
import com.keepit.common.util.{ DollarAmount, LinkElement, DescriptionElements }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ NotificationCategory, Organization, OrganizationRepo, UserEmailAddressRepo, _ }
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.RewardCreditApplied
import com.keepit.payments.AccountEventAction.RewardCredit
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.slack.models.{ SlackChannelName, SlackMessageRequest }
import play.api.Mode

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

@ImplementedBy(classOf[AccountEventTrackingCommanderImpl])
trait AccountEventTrackingCommander {
  def track(event: AccountEvent)(implicit session: RWSession): AccountEvent
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
    inhouseSlackClient: InhouseSlackClient,
    stripeClient: StripeClient,
    mode: play.api.Mode.Mode,
    airbrake: AirbrakeNotifier,
    activityCommander: ActivityLogCommander,
    creditRewardRepo: CreditRewardRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    eliza: ElizaServiceClient,
    creditRewardInfoCommander: CreditRewardInfoCommander,
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

    Future.sequence(Seq(
      notifyOfTransaction(event).imap(_ => ()),
      notifyOfFailedCharge(event).imap(_ => ()),
      notifyOfError(event).imap(_ => ()),
      reportToSlack(event).imap(_ => ()),
      sendNotificationToMembers(event).imap(_ => ())
    )).imap(_ => ())
  } else Future.successful(())

  // todo(Léo): *temporary* this was copied straight from PaymentProcessingCommander
  private val reportingLock = new ReactiveLock(1) // guarantees event reporting order
  private def reportToSlack(msg: DescriptionElements, channel: SlackChannelName): Future[Unit] = {
    reportingLock.withLockFuture {
      inhouseSlackClient.sendToSlack(InhouseSlackChannel.BILLING_ALERTS, SlackMessageRequest.inhouse(msg)).imap(_ => ())
    }
  }

  private def sendNotificationToMembers(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Seq[Id[User]]] = {
    checkingParameters(event) {
      event.action match {
        case RewardCredit(id) =>
          val (description, members) = db.readOnlyMaster { implicit s =>
            val description = creditRewardInfoCommander.getDescription(creditRewardRepo.get(id))
            val members = orgMembershipRepo.getAllByOrgId(org.id.get).map(_.userId)
            (description, members)
          }
          val sent = members.toSeq.map { member =>
            eliza.sendNotificationEvent(
              RewardCreditApplied(
                recipient = Recipient.fromUser(member),
                time = currentDateTime,
                description = DescriptionElements.formatPlain(description),
                org.id.get)).map(_ => member)
          }
          Future.sequence(sent)
        case _ =>
          Future.successful(Seq.empty)
      }
    }
  }

  private def reportToSlack(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Seq[SlackChannelName]] = {
    import DescriptionElements._
    checkingParameters(event) {
      lazy val msg = {
        val info = activityCommander.buildSimpleEventInfo(event)
        DescriptionElements(
          "[", org.name --> LinkElement(s"https://admin.kifi.com/admin/payments/getAccountActivity?orgId=${org.id.get}&page=0"), "]",
          info.description, info.creditChange
        )
      }
      Future.sequence(toSlackChannels(event.action.eventType).map { channel =>
        reportToSlack(msg, channel).imap(_ => channel)
      })
    }
  }

  private def toSlackChannels(eventType: AccountEventKind): Seq[SlackChannelName] = {
    import AccountEventKind._
    Seq(
      billing.contains(eventType) -> "#billing-alerts",
      orgGrowth.contains(eventType) -> "#org-members"
    ).collect { case (true, ch) => SlackChannelName(ch) }
  }

  private def notifyOfError(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Boolean] = {
    checkingParameters(event) {
      if (event.action.eventType == AccountEventKind.IntegrityError) {
        airbrake.notify(s"Account ${event.accountId} has an integrity error: ${event.action}")
        Future.successful(true)
      } else Future.successful(false)
    }
  }

  private def notifyOfTransaction(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Boolean] = {
    checkingParameters(event) {
      event.paymentCharge match {
        case Some(amount) if amount > DollarAmount.ZERO => notifyOfCharge(account, paymentMethod.get.stripeToken, amount, event.chargeId.get).imap(_ => true)
        case Some(amount) if amount < DollarAmount.ZERO => notifyOfRefund(account, paymentMethod.get.stripeToken, -amount, event.chargeId.get).imap(_ => true)
        case None => Future.successful(false)
      }
    }
  }

  private def notifyOfCharge(account: PaidAccount, stripeToken: StripeToken, amount: DollarAmount, chargeId: StripeTransactionId): Future[Unit] = {
    val lastFourFuture = stripeClient.getLastFourDigitsOfCard(stripeToken)
    val (userContacts, org) = db.readOnlyReplica { implicit session =>
      val userContacts = account.userContacts.flatMap { userId =>
        Try(emailRepo.getByUser(userId)).toOption
      }
      (userContacts, orgRepo.get(account.orgId))
    }
    val emails = (account.emailContacts ++ userContacts).distinct

    lastFourFuture.map { lastFour =>
      val subject = s"We've charged you card for your Kifi Team ${org.name}"
      val htmlBody = s"""|<p>Your card on file ending in $lastFour has been charged $amount (ref. ${chargeId.id}).<br/>
      |For more details please consult <a href="${pathCommander.pathForOrganization(org).absolute}/settings/activity">your account history</a>.</p>
      |
      |<p>Thanks,
      |The Kifi Team</p>
      """.stripMargin
      val textBody = s"""|Your card on file ending in $lastFour has been charged $amount (ref. ${chargeId.id}).
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

  private def notifyOfRefund(account: PaidAccount, stripeToken: StripeToken, amount: DollarAmount, refundId: StripeTransactionId): Future[Unit] = {
    val lastFourFuture = stripeClient.getLastFourDigitsOfCard(stripeToken)
    val (userContacts, org) = db.readOnlyReplica { implicit session =>
      val userContacts = account.userContacts.flatMap { userId =>
        Try(emailRepo.getByUser(userId)).toOption
      }
      (userContacts, orgRepo.get(account.orgId))
    }
    val emails = (account.emailContacts ++ userContacts).distinct

    lastFourFuture.map { lastFour =>
      val subject = s"We've refunded a charge made for your Kifi Team ${org.name}"
      val htmlBody = s"""|<p>Your card on file ending in $lastFour has been refunded $amount (ref. ${refundId.id}).<br/>
      |For more details please consult <a href="${pathCommander.pathForOrganization(org).absolute}/settings/activity">your account history</a>.</p>
      |
      |<p>Thanks,
      |The Kifi Team</p>
      """.stripMargin
      val textBody = s"""|Your card on file ending in $lastFour has been refunded $amount (ref. ${refundId.id}).
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

  private def notifyOfFailedCharge(event: AccountEvent)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Future[Boolean] = {
    checkingParameters(event) {
      event.action match {
        case AccountEventAction.ChargeFailure(_, _, _) | AccountEventAction.MissingPaymentMethod() =>
          doNotifyOfFailedCharge(event.id.get, event.action); Future.successful(true)
        case _ => Future.successful(false)
      }
    }
  }

  private def doNotifyOfFailedCharge(eventId: Id[AccountEvent], reason: AccountEventAction)(implicit account: PaidAccount, org: Organization, paymentMethod: Option[PaymentMethod]): Unit = {
    val subject = s"We failed to charge the card of team ${org.name}"
    val htmlBody = s"""|<p>We failed to charge <a href="https://admin.kifi.com/admin/organization/${org.id.get}">${org.name}</a> for their ${account.owed.toDollarString} outstanding balance.
    |${paymentMethod.map(p => s"We tried their payment method with id ${p.id.get}.") getOrElse "We couldn't find their payment method."}
    |This ended up with a $reason event with id $eventId.
    |For more details please consult <a href="https://admin.kifi.com/admin/payments/getAccountActivity?orgId=${org.id.get}&page=0}">their account history</a>.</p>
    |
    |<p>And to whom it may concern, make them pay.</p>
    |
    |<p>Thanks,
    |The Kifi Team</p>
    """.stripMargin
    val textBody = s"""|We failed to charge ${org.name} for their ${account.owed.toDollarString} outstanding balance.
    |${paymentMethod.map(p => s"We tried their payment method with id ${p.id.get}.") getOrElse "We couldn't find their payment method."}
    |This ended up with a $reason event with id $eventId.
    |For more details please consult their account history: https://admin.kifi.com/admin/payments/getAccountActivity?orgId=${org.id.get}&page=0}
    |
    |And to whom it may concern, make them pay.
    |
    |Thanks,
    |The Kifi Team
    """.stripMargin
    db.readWrite { implicit session =>
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.BILLING,
        fromName = Some("Kifi Billing"),
        to = Seq(EmailAddress("billingalerts@kifi.com")),
        subject = subject,
        htmlBody = htmlBody,
        textBody = Some(textBody),
        category = NotificationCategory.NonUser.BILLING
      ))
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
