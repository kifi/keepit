package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.TwitterWaitlistCommander
import com.keepit.commanders.emails.EmailTemplateSender
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ SystemEmailAddress, EmailAddress }
import com.keepit.common.mail.template.{ TemplateOptions, EmailToSend }
import com.keepit.model._
import play.twirl.api.Html
import views.html

import scala.concurrent.{ ExecutionContext, Future }

class AdminTwitterWaitlistController @Inject() (
    val userActionsHelper: UserActionsHelper,
    twitterWaitlistCommander: TwitterWaitlistCommander,
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    emailTemplateSender: EmailTemplateSender,
    userValueRepo: UserValueRepo,
    twitterSyncStateRepo: TwitterSyncStateRepo,
    implicit val ec: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions {

  def getWaitlist() = AdminUserPage { implicit request =>
    val entriesList = twitterWaitlistCommander.getWaitlist
    Ok(html.admin.twitterWaitlist(entriesList))
  }

  def acceptUser(userId: Id[User], handle: String) = AdminUserPage { implicit request =>
    val result = twitterWaitlistCommander.acceptUser(userId, handle).right.map { syncState =>
      val (lib, owner) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(syncState.libraryId)
        val owner = userRepo.get(lib.ownerId)
        (lib, owner)
      }
      val libraryPath = Library.formatLibraryPath(owner.username, lib.slug)
      (syncState, libraryPath, owner.primaryEmail)
    }
    Ok(html.admin.twitterWaitlistAccept(result))
  }

  def sendAcceptEmail(syncStateId: Id[TwitterSyncState], userId: Id[User], safe: Boolean) = AdminUserPage.async { request =>

    val (user, email, libraryPath, alreadySent) = db.readOnlyReplica { implicit session =>
      val user = userRepo.get(userId)
      val email = userEmailAddressRepo.getByUser(userId)
      val sync = twitterSyncStateRepo.get(syncStateId)
      val alreadySent = userValueRepo.getValue(user.id.get, UserValues.twitterSyncAcceptSent)

      val library = libraryRepo.get(sync.libraryId)
      val libraryPath = Library.formatLibraryPath(user.username, library.slug)

      (user, email, libraryPath, alreadySent)
    }
    val libPathEncoded = java.net.URLEncoder.encode(libraryPath, "UTF-8")

    if (safe && alreadySent) {
      Future.successful(BadRequest("Email already sent. Not expecting to see this? Ask Andrew"))
    } else {
      db.readWrite { implicit session =>
        userValueRepo.setValue(user.id.get, UserValueName.TWITTER_SYNC_ACCEPT_SENT, true)
      }

      val emailToSend = EmailToSend(
        fromName = Some(Right("Ashley McGregor Dey")),
        from = EmailAddress("ashley@kifi.com"),
        subject = s"${user.firstName} your wait is over: Twitter deep search is ready for you",
        to = Right(email),
        category = NotificationCategory.User.WAITLIST,
        htmlTemplate = views.html.email.black.twitterAccept(userId, libraryPath, libPathEncoded),
        textTemplate = Some(views.html.email.black.twitterAccept(userId, libraryPath, libPathEncoded)),
        campaign = Some("passwordReset"),
        templateOptions = Seq(TemplateOptions.CustomLayout).toMap
      )
      emailTemplateSender.send(emailToSend).map { result =>
        Ok(s"Email sent to $email")
      }
    }

  }

}
