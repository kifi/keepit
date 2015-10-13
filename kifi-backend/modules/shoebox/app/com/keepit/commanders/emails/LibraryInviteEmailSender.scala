package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, ProcessedImageSize, LibraryImageCommander }
import com.keepit.model._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ PostOffice, EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.TemplateOptions._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.mail.template.helpers.fullName

import scala.concurrent.{ ExecutionContext, Future }

class LibraryInviteEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    userEmailRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    orgRepo: OrganizationRepo,
    libraryImageCommander: LibraryImageCommander,
    localUserExperimentCommander: LocalUserExperimentCommander,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendInvite(invite: LibraryInvite, isPlainEmail: Boolean = true)(implicit publicIdConfig: PublicIdConfiguration): Future[Option[ElectronicMail]] = {
    val toRecipientOpt: Option[Either[Id[User], EmailAddress]] =
      if (invite.userId.isDefined) Some(Left(invite.userId.get))
      else if (invite.emailAddress.isDefined) Some(Right(invite.emailAddress.get))
      else None

    toRecipientOpt map { toRecipient =>
      val (library, libraryInfo) = db.readWrite { implicit session =>
        val library = libraryRepo.get(invite.libraryId)
        val org = library.organizationId.map { id => orgRepo.get(id) }
        val libOwner = basicUserRepo.load(library.ownerId)
        val inviter = basicUserRepo.load(invite.inviterId)
        val libImage = libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.Large.idealSize).map(_.asInfo)
        val libraryInfo = LibraryInfo.fromLibraryAndOwner(library, libImage, libOwner, org, Some(inviter))
        (library, libraryInfo)
      }

      val usePlainEmail = isPlainEmail || invite.userId.map { id => localUserExperimentCommander.userHasExperiment(id, UserExperimentType.PLAIN_EMAIL) }.getOrElse(false)
      val trimmedInviteMsg = invite.message map (_.trim) filter (_.nonEmpty)
      val fromUserId = invite.inviterId
      val authToken = invite.authToken
      val emailToSend = EmailToSend(
        fromName = Some(Left(invite.inviterId)),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = if (invite.access == LibraryAccess.READ_ONLY) s"An invitation to a Kifi library: ${libraryInfo.name}" else s"I want to collaborate with you on ${libraryInfo.name}",
        to = toRecipient,
        category = toRecipient.fold(_ => NotificationCategory.User.LIBRARY_INVITATION, _ => NotificationCategory.NonUser.LIBRARY_INVITATION),
        htmlTemplate = {
          if (usePlainEmail) {
            views.html.email.libraryInvitationPlain(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, library, libraryInfo, authToken, invite.access)
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
}
