package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LibraryInfo
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.TemplateOptions._
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.common.mail.template.helpers.fullName

import scala.concurrent.Future

class LibraryInviteEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def inviteUserToLibrary(toUserRecipient: Either[Id[User], EmailAddress], fromUserId: Id[User], libraryId: Id[Library], inviteMsg: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration): Future[ElectronicMail] = {
    val (library, libraryInfo, libraryOwner) = db.readWrite { implicit session =>
      val library = libraryRepo.get(libraryId)
      val libOwner = basicUserRepo.load(library.ownerId)
      val numKeeps = keepRepo.getCountByLibrary(library.id.get)
      val libraryInfo = LibraryInfo.fromLibraryAndOwner(library, libOwner, numKeeps)
      (library, libraryInfo, libOwner)
    }

    val emailToSend = EmailToSend(
      fromName = Some(Left(fromUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${fullName(fromUserId)} invited you to follow ${library.name}!",
      to = toUserRecipient,
      category = NotificationCategory.User.LIBRARY_INVITATION,
      htmlTemplate = views.html.email.libraryInvitation(toUserRecipient.left.toOption, fromUserId, inviteMsg, libraryInfo),
      textTemplate = Some(views.html.email.libraryInvitationText(toUserRecipient.left.toOption, fromUserId, inviteMsg, libraryInfo)),
      templateOptions = Seq(CustomLayout).toMap
    )
    emailTemplateSender.send(emailToSend)
  }
}
