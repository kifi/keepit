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
import com.keepit.social.SocialNetworks
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
    socialUserInfoRepo: SocialUserInfoRepo,
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

  def updateLibraryFromTwitterProfile(handle: String, userId: Id[User]) = AdminUserAction.async { request =>
    db.readOnlyReplica { implicit session =>
      twitterSyncStateRepo.getByHandleAndUserIdUsed(TwitterHandle(handle), userId).map { sync =>
        (sync, socialUserInfoRepo.getByUser(userId).find(s => s.networkType == SocialNetworks.TWITTER && s.username.isDefined))
      }
    }.collect {
      case (sync, Some(sui)) =>
        twitterWaitlistCommander.syncTwitterShow(sync.twitterHandle, sui, sync.libraryId).map { res =>
          Ok(res.toString)
        }
    }.getOrElse(Future.successful(Ok("None")))
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
