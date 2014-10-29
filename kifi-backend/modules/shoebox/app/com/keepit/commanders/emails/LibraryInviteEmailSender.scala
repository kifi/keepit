package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LibraryInfo
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
import com.keepit.model.{ UserEmailAddressRepo, UserRepo, NotificationCategory, User, LibraryInvite, LibraryRepo, KeepRepo }
import com.keepit.social.BasicUser

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

class LibraryInviteEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    userEmailRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def inviteUserToLibrary(invite: LibraryInvite)(implicit publicIdConfig: PublicIdConfiguration): Future[Option[ElectronicMail]] = {
    val toRecipientOpt: Option[Either[Id[User], EmailAddress]] =
      if (invite.userId.isDefined) Some(Left(invite.userId.get))
      else if (invite.emailAddress.isDefined) Some(Right(invite.emailAddress.get))
      else None

    toRecipientOpt map { toRecipient =>
      val (library, libraryInfo) = db.readWrite { implicit session =>
        val library = libraryRepo.get(invite.libraryId)
        val libOwner = basicUserRepo.load(library.ownerId)
        val inviter = basicUserRepo.load(invite.inviterId)
        val numKeeps = keepRepo.getCountByLibrary(library.id.get)
        val libraryInfo = LibraryInfo.fromLibraryAndOwner(library, libOwner, numKeeps, Some(inviter))
        (library, libraryInfo)
      }

      val trimmedInviteMsg = invite.message map (_.trim) filter (_.nonEmpty)
      val fromUserId = invite.inviterId
      val fromAddress = db.readOnlyReplica { implicit session => userEmailRepo.getByUser(invite.inviterId).address }
      val passPhrase = toRecipient match {
        case Left(_: Id[User]) => None
        case Right(_: EmailAddress) => Some(invite.passPhrase)
      }
      var authToken = invite.authToken
      val emailToSend = EmailToSend(
        fromName = Some(Left(invite.inviterId)),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = s"${fullName(fromUserId)} invited you to follow ${library.name}!",
        to = toRecipient,
        category = NotificationCategory.User.LIBRARY_INVITATION,
        htmlTemplate = views.html.email.libraryInvitation(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, libraryInfo, passPhrase, authToken),
        textTemplate = Some(views.html.email.libraryInvitationText(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, libraryInfo, passPhrase, authToken)),
        templateOptions = Seq(CustomLayout).toMap,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> fromAddress)),
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
