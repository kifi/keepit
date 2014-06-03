package com.keepit.common.mail

import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{SafeFuture, FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.model._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.heimdal._
import com.keepit.common.time._
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.NonUserKinds

trait MailSenderPlugin {
  def processMail(mail: ElectronicMail)
  def processOutbox()
}

class MailSenderPluginImpl @Inject() (
    actor: ActorInstance[MailSenderActor],
    val scheduling: SchedulingProperties) //only on leader
  extends Logging with MailSenderPlugin with SchedulerPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnLeader(actor.system, 5 seconds, 5 seconds, actor.ref, ProcessOutbox)
  }

  override def processOutbox() { actor.ref ! ProcessOutbox }
  override def processMail(mail: ElectronicMail) { actor.ref ! ProcessMail(mail) }
}

private[mail] case class ProcessOutbox()
private[mail] case class ProcessMail(mailId: ElectronicMail)


private[mail] class MailSenderActor @Inject() (
    db: Database,
    mailRepo: ElectronicMailRepo,
    emailOptOutRepo: EmailOptOutRepo,
    userNotifyPreferenceRepo: UserNotifyPreferenceRepo,
    emailAddressRepo: UserEmailAddressRepo,
    airbrake: AirbrakeNotifier,
    mailProvider: MailProvider,
    heimdalContextBuiler: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ProcessOutbox =>
      val emailsToSend = db.readOnly { implicit s =>
          mailRepo.outbox() flatMap { email =>
            try {
              Some(mailRepo.get(email))
            } catch {
              case ex: Throwable =>
                airbrake.notify(ex)
                None
            }
        }
      }

      emailsToSend.foreach { mail =>
        self ! ProcessMail(mail)
      }
    case ProcessMail(mail) =>
      log.info(s"Processing email to send: ${mail.id.getOrElse(mail.externalId)}")
      val newMail = takeOutOptOuts(mail).clean()
      if (newMail.state != ElectronicMailStates.OPT_OUT) {
        log.info(s"Sending email: ${newMail.id.getOrElse(newMail.externalId)}")
        mailProvider.sendMail(newMail)
        reportEmailNotificationSent(newMail)
      } else {
        log.info(s"Not sending email due to opt-out: ${newMail.id.getOrElse(newMail.externalId)}")
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  def takeOutOptOuts(mail: ElectronicMail) = { // say that 3 times fast
    val (newTo, newCC) = db.readOnly { implicit session =>
      val newTo = mail.to.filterNot(addressHasOptedOut(_, mail.category))
      val newCC = mail.cc.filterNot(addressHasOptedOut(_, mail.category))
      (newTo, newCC)
    }
    if (newTo.toSet != mail.to.toSet || newCC.toSet != mail.cc.toSet) {
      if (newTo.isEmpty) {
        db.readWrite { implicit session =>
          mailRepo.save(mail.copy(state = ElectronicMailStates.OPT_OUT))
        }
      } else {
        db.readWrite { implicit session =>
          mailRepo.save(mail.copy(to = newTo, cc = newCC))
        }
      }
    } else mail
  }

  def addressHasOptedOut(address: EmailAddressHolder, category: ElectronicMailCategory)(implicit session: RSession) = {
    emailOptOutRepo.hasOptedOut(address, category) || {
      emailAddressRepo.getByAddressOpt(address.address).map(_.userId) match {
        case None => // Email isn't owned by any user, send away!
          false
        case Some(userId) =>
          !userNotifyPreferenceRepo.canNotify(userId, category)
      }
    }
  }
  private val notificationsToBeReported = NotificationCategory.User.all ++ NotificationCategory.NonUser.all
  private def reportEmailNotificationSent(email: ElectronicMail): Unit = {
    if (notificationsToBeReported.contains(email.category)) {
      val sentAt = currentDateTime
      SafeFuture {
        val contextBuilder =  heimdalContextBuiler()
        contextBuilder += ("action", "sent")
        contextBuilder.addEmailInfo(email)

        val (to, cc) = db.readOnly { implicit session =>
          val cc: Seq[Either[Id[User], String]] = email.cc.flatMap { address => emailAddressRepo.getByAddress(address.address) match {
            case Seq() => Seq(Right(address.address))
            case emailAddresses => emailAddresses.map { emailAddress => Left(emailAddress.userId) }
          }}

          val to: Seq[Either[Id[User], String]] = email.to.flatMap { address => emailAddressRepo.getByAddress(address.address) match {
            case Seq() => Seq(Right(address.address))
            case emailAddresses => emailAddresses.map { emailAddress => Left(emailAddress.userId) }
          }}

          (to, cc)
        }

        val toUsers = to.collect { case Left(userId) => userId }
        val toNonUsers = to.collect { case Right(address) => address }

        val ccUsers = cc.collect { case Left(userId) => userId }
        val ccNonUsers = cc.collect { case Right(address) => address }

        if (toUsers.nonEmpty) { contextBuilder += ("toUsers", toUsers.map(_.id)) }
        if (toNonUsers.nonEmpty) { contextBuilder += ("toNonUsers", toNonUsers) }

        if (ccUsers.nonEmpty) { contextBuilder += ("ccUsers", ccUsers.map(_.id)) }
        if (ccNonUsers.nonEmpty) { contextBuilder += ("ccNonUsers", ccNonUsers) }

        val context = contextBuilder.build

        if (NotificationCategory.User.all.contains(email.category)) {
          (toUsers ++ ccUsers).toSet[Id[User]].foreach { userId => heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.WAS_NOTIFIED, sentAt)) }
        }

        if (NotificationCategory.NonUser.all.contains(email.category)) {
          (toNonUsers ++ ccNonUsers).toSet[String].foreach { address => heimdal.trackEvent(NonUserEvent(address, NonUserKinds.email, context, NonUserEventTypes.WAS_NOTIFIED, sentAt)) }
        }
      }
    }
  }
}
