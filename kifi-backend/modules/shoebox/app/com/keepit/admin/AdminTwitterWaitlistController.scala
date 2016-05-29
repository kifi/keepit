package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.{ TwitterPublishingCommander, PathCommander, TwitterWaitlistCommander }
import com.keepit.commanders.emails.EmailTemplateSender
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ SystemEmailAddress, EmailAddress }
import com.keepit.common.mail.template.{ TemplateOptions, EmailToSend }
import com.keepit.model._
import com.keepit.social.twitter.TwitterHandle
import play.twirl.api.Html
import views.html

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class AdminTwitterWaitlistController @Inject() (
    val userActionsHelper: UserActionsHelper,
    twitterWaitlistCommander: TwitterWaitlistCommander,
    twitterWaitlistRepo: TwitterWaitlistRepo,
    twitterPublishingCommander: TwitterPublishingCommander,
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libPathCommander: PathCommander,
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
    val result = twitterWaitlistCommander.acceptUser(userId, TwitterHandle(handle)).right.map { syncState =>
      val (lib, email) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(syncState.libraryId)
        val email = Try(userEmailAddressRepo.getByUser(userId)).toOption
        (lib, email)
      }
      val libraryPath = libPathCommander.getPathForLibrary(lib)
      (syncState, libraryPath, email)
    }
    Ok(html.admin.twitterWaitlistAccept(result))
  }

  def viewAcceptedUser(userId: Id[User]) = AdminUserPage { implicit request =>
    val states = db.readOnlyMaster { implicit s => twitterSyncStateRepo.getByUserIdUsed(userId) }
    if (states.size != 1) {
      BadRequest(s"user $userId has ${states.size} states")
    } else {
      val syncState = states(0)
      val (lib, owner, email) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(syncState.libraryId)
        val owner = userRepo.get(lib.ownerId)
        val email = Try(userEmailAddressRepo.getByUser(userId)).toOption
        (lib, owner, email)
      }
      val libraryPath = libPathCommander.getPathForLibrary(lib)
      Ok(html.admin.twitterWaitlistAccept(Right(syncState, libraryPath, email)))
    }
  }

  def processWaitlist() = AdminUserAction { request =>
    twitterWaitlistCommander.processQueue()
    Ok
  }

  def sendAcceptEmail(syncStateId: Id[TwitterSyncState], safe: Boolean) = AdminUserPage.async { request =>
    val (user, email, libraryPath, alreadySent) = db.readOnlyReplica { implicit session =>
      val sync = twitterSyncStateRepo.get(syncStateId)
      val userId = sync.userId.get
      val user = userRepo.get(userId)
      val email = userEmailAddressRepo.getByUser(userId)
      val alreadySent = userValueRepo.getValue(userId, UserValues.twitterSyncAcceptSent)

      val library = libraryRepo.get(sync.libraryId)
      val libraryPath = libPathCommander.getPathForLibrary(library)

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
        htmlTemplate = views.html.email.black.twitterAccept(user.id.get, libraryPath, libPathEncoded),
        textTemplate = Some(views.html.email.black.twitterAccept(user.id.get, libraryPath, libPathEncoded)),
        campaign = Some("passwordReset"),
        templateOptions = Seq(TemplateOptions.CustomLayout).toMap
      )
      emailTemplateSender.send(emailToSend).map { result =>
        Ok(s"Email sent to $email")
      }
    }
  }

  def tweetAtUserLibrary(libraryId: Id[Library]) = AdminUserAction { implicit request =>
    twitterPublishingCommander.announceNewTwitterLibrary(libraryId) match {
      case Success(status) =>
        db.readWrite { implicit s =>
          twitterWaitlistRepo.getByUser(libraryRepo.get(libraryId).ownerId) map { entry =>
            twitterWaitlistRepo.save(entry.withState(TwitterWaitlistEntryStates.ANNOUNCED))
          }
        }
        Ok(status.toString)
      case Failure(e) =>
        db.readWrite { implicit s =>
          twitterWaitlistRepo.getByUser(libraryRepo.get(libraryId).ownerId) map { entry =>
            twitterWaitlistRepo.save(entry.withState(TwitterWaitlistEntryStates.ANNOUNCE_FAIL))
          }
        }
        InternalServerError(e.toString)
    }
  }

  def markAsTwitted(userId: Id[User]) = AdminUserAction { implicit request =>
    val entries = db.readWrite { implicit s =>
      twitterWaitlistRepo.getByUser(userId) map { entry =>
        twitterWaitlistRepo.save(entry.withState(TwitterWaitlistEntryStates.ANNOUNCED))
      }
    }
    Ok(s"Done! ${entries.mkString(";")}")
  }

}
