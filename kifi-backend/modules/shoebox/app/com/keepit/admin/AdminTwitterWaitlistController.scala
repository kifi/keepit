package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.{ TwitterPublishingCommander, PathCommander, TwitterWaitlistCommander }
import com.keepit.commanders.emails.{ EmailSenderProvider, EmailTemplateSender }
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
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
    emailSenderProvider: EmailSenderProvider,
    twitterWaitlistRepo: TwitterWaitlistRepo,
    twitterPublishingCommander: TwitterPublishingCommander,
    airbrake: AirbrakeNotifier,
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
      twitterSyncStateRepo.getByHandleAndUserIdUsed(TwitterHandle(handle), userId, SyncTarget.Tweets).map { sync =>
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
        sendTwitterEmailToLib(libraryId)
        SeeOther("https://twitter.com/kifi/status/" + status.getId())
      case Failure(e) =>
        db.readWrite { implicit s =>
          twitterWaitlistRepo.getByUser(libraryRepo.get(libraryId).ownerId) map { entry =>
            twitterWaitlistRepo.save(entry.withState(TwitterWaitlistEntryStates.ANNOUNCE_FAIL))
          }
        }
        InternalServerError(e.toString)
    }
  }

  private def doEmailUsersWithTwitterLibs(syncs: Seq[TwitterSyncState], max: Int) = {
    val res = db.readOnlyMaster { implicit s =>
      syncs filter (_.userId.isDefined) map { sync =>
        val userId = sync.userId.get
        val user = userRepo.get(userId)
        val library = libraryRepo.get(sync.libraryId)
        val suiOpt = socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.TWITTER).filter(_.state == SocialUserInfoStates.FETCHED_USING_SELF).filter(_.credentials.nonEmpty)
        (user, library, suiOpt, sync)
      } filter {
        case (user, library, suiOpt, sync) =>
          val validUser = user.isActive && library.isActive && suiOpt.isDefined && sync.state == TwitterSyncStateStates.ACTIVE
          validUser && userValueRepo.getUserValue(user.id.get, UserValueName.SENT_TWITTER_SYNC_EMAIL).isEmpty
      } take max
    } map {
      case (user, library, _, _) =>
        db.readOnlyMaster { implicit s =>
          val userId = user.id.get
          val email = userEmailAddressRepo.getPrimaryByUser(userId).map(_.address).getOrElse(userEmailAddressRepo.getByUser(userId))
          val libraryUrl = libPathCommander.libraryPage(library)
          (user, library, email, libraryUrl)
        }
    } map {
      case (user, library, email, libraryUrl) =>
        val syncKey = twitterWaitlistCommander.getSyncKey(user.externalId)
        emailSenderProvider.twitterWaitlistOldUsers.sendToUser(email, user.id.get, libraryUrl.absolute, library.keepCount, syncKey).onComplete {
          case Failure(ex) =>
            db.readWrite { implicit s =>
              userValueRepo.setValue(user.id.get, UserValueName.SENT_TWITTER_SYNC_EMAIL, "fail")
            }
            airbrake.notify(s"could not email $user using $email on lib $library", ex)
          case Success(mail) =>
            db.readWrite { implicit s =>
              userValueRepo.setValue(user.id.get, UserValueName.SENT_TWITTER_SYNC_EMAIL, "success")
            }
            log.info(s"email sent to $email $user")
        }
    }
    Ok(s"Sending emails to ${res.size} users")
  }

  def emailUsersWithTwitterLibs(max: Int) = AdminUserAction { implicit request =>
    val syncs = db.readOnlyMaster { implicit s =>
      twitterSyncStateRepo.getActiveByTarget(SyncTarget.Tweets)
    }
    doEmailUsersWithTwitterLibs(syncs, max)
  }

  def testEmailUsersWithTwitterLibs(max: Int, userIdsString: String) = AdminUserAction { implicit request =>
    val userIds = userIdsString.split(",").map(_.toInt).map(Id[User](_))
    val syncs = db.readOnlyMaster { implicit s =>
      twitterSyncStateRepo.getByUserIds(userIds.toSet)
    }
    val res = doEmailUsersWithTwitterLibs(syncs, max)
    Thread.sleep(5000) //this is bad code, use only for testing async stuff and i can be done better. should be removed from codebase by tomorrow
    db.readWrite { implicit s =>
      userIds map { userId =>
        val value = userValueRepo.getUserValue(userId, UserValueName.SENT_TWITTER_SYNC_EMAIL).getOrElse(throw new Exception(s"user $userId has no userValue"))
        if (value.value != "success") throw new Exception(s"user $userId has no success: $value")
        userValueRepo.clearValue(userId, UserValueName.SENT_TWITTER_SYNC_EMAIL)
      }
    }
    res
  }

  def markAsTwitted(userId: Id[User]) = AdminUserAction { implicit request =>
    val states = db.readWrite { implicit s =>
      twitterWaitlistRepo.getByUser(userId) map { entry =>
        twitterWaitlistRepo.save(entry.withState(TwitterWaitlistEntryStates.ANNOUNCED))
      }
      twitterSyncStateRepo.getByUserIdUsed(userId)
    }
    if (states.nonEmpty) {
      sendTwitterEmailToLib(states.head.libraryId)
      Ok(s"Done! ${states.mkString(";")}")
    } else InternalServerError(s"no states for user $userId")
  }

  def getFavSyncLink(userId: Id[User]) = AdminUserAction { implicit request =>
    val user = db.readOnlyMaster { implicit s => userRepo.get(userId) }
    Ok(twitterWaitlistCommander.getSyncKey(user.externalId))
  }

  private def sendTwitterEmailToLib(libraryId: Id[Library]) = {
    val (email, libraryUrl, userId, count, externalUserId) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(libraryId)
      val userId = library.ownerId
      val user = userRepo.get(userId)
      val email = userEmailAddressRepo.getPrimaryByUser(userId).map(_.address).getOrElse(userEmailAddressRepo.getByUser(userId))
      val libraryUrl = libPathCommander.libraryPage(library)
      (email, libraryUrl, userId, library.keepCount, user.externalId)
    }
    val syncKey = twitterWaitlistCommander.getSyncKey(externalUserId)
    emailSenderProvider.twitterWaitlist.sendToUser(email, userId, libraryUrl.absolute, count, syncKey).onComplete {
      case Failure(ex) =>
        db.readWrite { implicit s =>
          userValueRepo.setValue(userId, UserValueName.SENT_TWITTER_SYNC_EMAIL, "fail")
        }
        airbrake.notify(s"could not email $userId using $email on lib $libraryUrl", ex)
      case Success(mail) =>
        db.readWrite { implicit s =>
          userValueRepo.setValue(userId, UserValueName.SENT_TWITTER_SYNC_EMAIL, "success")
        }
        log.info(s"email sent to $email $userId")
    }
  }

}
