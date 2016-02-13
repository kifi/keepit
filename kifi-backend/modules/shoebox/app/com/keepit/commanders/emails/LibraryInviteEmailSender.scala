package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryImageCommander, LocalUserExperimentCommander, ProcessedImageSize }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.TemplateOptions._
import com.keepit.common.mail.{ ElectronicMail, EmailAddress, SystemEmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.heimdal.{ ContextBoolean, HeimdalContext }
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }

class LibraryInviteEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    basicUserRepo: BasicUserRepo,
    libraryRepo: LibraryRepo,
    libraryInviteRepo: LibraryInviteRepo,
    orgRepo: OrganizationRepo,
    libraryImageCommander: LibraryImageCommander,
    localUserExperimentCommander: LocalUserExperimentCommander,
    clock: Clock,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendInvite(invite: LibraryInvite, isPlainEmail: Boolean = true)(implicit publicIdConfig: PublicIdConfiguration): Future[Option[ElectronicMail]] = {
    val toRecipientOpt: Option[Either[Id[User], EmailAddress]] =
      if (invite.userId.isDefined) Some(Left(invite.userId.get))
      else if (invite.emailAddress.isDefined) Some(Right(invite.emailAddress.get))
      else None

    toRecipientOpt map { toRecipient =>
      val (library, libraryInfo, teamName) = db.readWrite { implicit session =>
        val library = libraryRepo.get(invite.libraryId)
        val org = library.organizationId.map { id => orgRepo.get(id) }
        val libOwner = basicUserRepo.load(library.ownerId)
        val inviter = basicUserRepo.load(invite.inviterId)
        val libImage = libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.Large.idealSize).map(_.asInfo)
        val libraryInfo = LibraryInfo.fromLibraryAndOwner(library, libImage, libOwner, org, Some(inviter))
        val teamName: Option[String] = library.organizationId map { id => orgRepo.get(id).name }
        (library, libraryInfo, teamName)
      }

      val usePlainEmail = isPlainEmail || invite.userId.map { id => localUserExperimentCommander.userHasExperiment(id, UserExperimentType.PLAIN_EMAIL) }.getOrElse(false)
      val trimmedInviteMsg = invite.message map (_.trim) filter (_.nonEmpty)
      val fromUserId = invite.inviterId
      val authToken = invite.authToken
      val emailToSend = EmailToSend(
        fromName = Some(Left(invite.inviterId)),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = if (invite.access == LibraryAccess.READ_ONLY) s"Invite to join my Kifi library on ${libraryInfo.name}" else s"Invite to collaborate on my Kifi library ${libraryInfo.name}",
        to = toRecipient,
        category = toRecipient.fold(_ => NotificationCategory.User.LIBRARY_INVITATION, _ => NotificationCategory.NonUser.LIBRARY_INVITATION),
        htmlTemplate = {
          if (usePlainEmail) {
            views.html.email.libraryInvitationPlain(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, library, libraryInfo, authToken, invite.access, teamName)
          } else {
            views.html.email.libraryInvitation(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, library, libraryInfo, authToken, invite.access)
          }
        },
        textTemplate = Some(views.html.email.libraryInvitationText(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, library, libraryInfo, authToken, invite.access)),
        templateOptions = Seq(CustomLayout).toMap,
        campaign = Some("na"),
        channel = Some("vf_email"),
        source = Some("library_invite")
      )
      emailTemplateSender.send(emailToSend) map (Some(_))
    } getOrElse {
      airbrake.notify(s"LibraryInvite does not have a recipient: $invite")
      Future.successful(None)
    }
  }

  def sendInviteReminder(invite: LibraryInvite): Future[Option[ElectronicMail]] = {
    val toRecipientOpt = {
      if (invite.userId.isDefined) Some(Left(invite.userId.get))
      else if (invite.emailAddress.isDefined) Some(Right(invite.emailAddress.get))
      else None
    }

    toRecipientOpt.map { toRecipient =>
      val auxiliaryData = HeimdalContext("reminder" -> ContextBoolean(true))
      val emailToSend = EmailToSend(
        fromName = Some(Right("Kifi")),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = s"Library Invite Reminder", // TODO(josh)
        to = toRecipient,
        category = toRecipient.fold(
          _ => NotificationCategory.User.LIBRARY_INVITATION_REMINDER,
          _ => NotificationCategory.NonUser.LIBRARY_INVITATION_REMINDER),
        htmlTemplate = views.html.email.libraryInvitationReminderPlain(),
        textTemplate = Some(views.html.email.libraryInvitationReminderText()),
        templateOptions = Seq(CustomLayout).toMap,
        auxiliaryData = Some(auxiliaryData),
        campaign = Some("na"),
        channel = Some("vf_email"),
        source = Some("library_invite_reminder") // TODO(josh)
      )

      emailTemplateSender.send(emailToSend).map { email =>
        // persist after we've confirmed the email was successfully sent
        val updatedInvite = invite.copy(lastReminderSentAt = Some(clock.now), remindersSent = invite.remindersSent + 1)
        db.readWrite { implicit session => libraryInviteRepo.save(updatedInvite) }

        Some(email)
      }
    }.getOrElse {
      Future.successful(None)
    }
  }
}
