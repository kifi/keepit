package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.common.mail.template.helpers.fullName

import scala.concurrent.Future

class LibraryInviteEmailSender @Inject() (
    db: Database,
    emailTemplateSender: EmailTemplateSender,
    basicUserRepo: BasicUserRepo,
    libraryRepo: LibraryRepo,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def inviteUserToLibrary(toUserRecipient: Either[Id[User], EmailAddress], fromUserId: Id[User], libraryId: Id[Library], inviteMsg: Option[String] = None): Future[ElectronicMail] = {
    val (library, libraryOwner) = db.readOnlyReplica { implicit session =>
      val library = libraryRepo.get(libraryId)
      val libOwner = basicUserRepo.load(library.ownerId)
      (library, libOwner)
    }
    val libLink = s"""https://www.kifi.com${Library.formatLibraryPath(libraryOwner.username, libraryOwner.externalId, library.slug)}"""
    val emailToSend = EmailToSend(
      fromName = Some(Left(fromUserId)),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${fullName(fromUserId)} invited you to follow ${library.name}!",
      to = toUserRecipient,
      category = NotificationCategory.User.LIBRARY_INVITATION,
      htmlTemplate = views.html.email.libraryInvitation(toUserRecipient.left.toOption, fromUserId, inviteMsg, library.name, library.description, libLink),
      campaign = Some("libraryInvite")
    )
    emailTemplateSender.send(emailToSend)
  }
}
