package com.keepit.common.mail

import scala.concurrent.duration._

import org.joda.time.Days

import com.google.inject.{ImplementedBy, Inject}
import play.api.Plugin

import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._

private case class NotifyAcceptedUser(userId: Id[User])
private case object ResendNotifications

private[mail] class InvitationMailActor @Inject() (
    db: Database,
    postOffice: LocalPostOffice,
    suiRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    userValueRepo: UserValueRepo,
    emailAddressRepo: EmailAddressRepo,
    invitationRepo: InvitationRepo,
    healthcheckPlugin: HealthcheckPlugin,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices
    ) extends FortyTwoActor(healthcheckPlugin) with Logging {

  private val ResentKey = "invitation_email_resent"
  private val TimeBeforeResend = Days.THREE

  def receive = {
    case NotifyAcceptedUser(userId) =>
      sendNotification(userId,
        "Congrats! You're in the KiFi Private Beta",
        views.html.email.invitationAccept(_).body)
    case ResendNotifications =>
      log.debug("Checking for invitations to resend")
      for {
        (inv, userId) <- db.readOnly { implicit s =>
          invitationRepo.getAdminAccepted().map(i => i -> suiRepo.get(i.recipientSocialUserId).userId.get)
        } if inv.updatedAt plus TimeBeforeResend isBefore clock.now()
      } {
        val resent =
          db.readOnly { implicit s => userValueRepo.getValue(userId, ResentKey) }.map(_.toBoolean).getOrElse(false)
        if (!resent) {
          sendNotification(userId,
            "Reminder: Congrats! You're in the KiFi Private Beta",
            views.html.email.invitationReminder(_).body)
          db.readWrite { implicit s => userValueRepo.setValue(userId, ResentKey, true.toString) }
        }
      }
  }

  private def sendNotification(userId: Id[User], subject: String, body: User => String) {
    db.readWrite { implicit session =>
      val user = userRepo.get(userId)
      for (address <- emailAddressRepo.getByUser(userId)) {
        postOffice.sendMail(ElectronicMail(
          senderUserId = None,
          from = EmailAddresses.CONGRATS,
          fromName = Some("KiFi Team"),
          to = List(address),
          subject = subject,
          htmlBody = body(user),
          category = PostOffice.Categories.INVITATION))
      }
    }
  }
}

@ImplementedBy(classOf[InvitationMailPluginImpl])
trait InvitationMailPlugin extends Plugin {
  def resendNotifications()
  def notifyAcceptedUser(userId: Id[User])
}

class InvitationMailPluginImpl @Inject()(
    actorFactory: ActorFactory[InvitationMailActor],
    val schedulingProperties: SchedulingProperties
    ) extends InvitationMailPlugin with SchedulingPlugin with Logging {

  override def enabled: Boolean = true

  private lazy val actor = actorFactory.get()

  def resendNotifications() {
    actor ! ResendNotifications
  }
  def notifyAcceptedUser(userId: Id[User]) {
    actor ! NotifyAcceptedUser(userId)
  }
  override def onStart() {
    log.info("Starting InvitationMailPluginImpl")
    scheduleTask(actorFactory.system, 10 seconds, 12 hours, actor, ResendNotifications)
  }
}
