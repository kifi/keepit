package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ PathCommander, TwitterWaitlistCommander }
import com.keepit.common.controller._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.twitter.TwitterHandle
import com.keepit.social.{ SocialGraphPlugin, SocialNetworks }
import play.api.libs.json.Json
import play.twirl.api.Html
import securesocial.core.SecureSocial
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try }

class TwitterWaitlistController @Inject() (
    commander: TwitterWaitlistCommander,
    socialRepo: SocialUserInfoRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    libPathCommander: PathCommander,
    db: Database,
    airbrakeNotifier: AirbrakeNotifier,
    socialGraphPlugin: SocialGraphPlugin,
    val userActionsHelper: UserActionsHelper,
    twitterSyncStateRepo: TwitterSyncStateRepo,
    libraryRepo: LibraryRepo,
    clock: Clock,
    userValueRepo: UserValueRepo,
    implicit val ec: ExecutionContext) extends UserActions with ShoeboxServiceController {

  def twitterWaitlistLandingRedirectHack() = twitterWaitlistLanding()

  def twitterWaitlistLanding() = MaybeUserAction { implicit request =>
    MarketingSiteRouter.marketingSite("twitter-home")
  }

  //DO NOT USE THE WORD *FAKE* IN THE ROUTE FOR THIS!!!
  def getFakeWaitlistPosition() = UserAction { request =>
    val (handleOpt, emailOpt) = db.readOnlyReplica { implicit session =>
      val twOpt = socialRepo.getByUser(request.userId).find(_.networkType == SocialNetworks.TWITTER).flatMap { info =>
        info.username.orElse(info.getProfileUrl.map(url => url.substring(url.lastIndexOf('/') + 1))).map(TwitterHandle(_))
      }
      val emailOpt = Try(userEmailAddressRepo.getByUser(request.userId)).toOption
      (twOpt, emailOpt)
    }
    val fakeFakePos = commander.getFakeWaitlistLength() + 3

    (handleOpt, emailOpt) match {
      case (Some(handle), Some(email)) =>
        commander.getFakeWaitlistPosition(request.userId, handle).map { realFakePos =>
          Ok(Json.obj(
            "email" -> email,
            "pos" -> realFakePos
          ))
        } getOrElse {
          Ok(Json.obj(
            "email" -> email,
            "pos" -> fakeFakePos
          ))
        }
      case (_, Some(email)) =>
        Ok(Json.obj(
          "email" -> email,
          "pos" -> fakeFakePos
        ))
      case _ =>
        log.warn(s"Couldn't find email for user, but they want to get on the twitter waitlist. poo? userid ${request.userId}, $handleOpt, $emailOpt")
        Ok(Json.obj(
          "pos" -> fakeFakePos
        ))
    }
  }

  //DO NOT USE THE WORD *FAKE* IN THE ROUTE FOR THIS!!!
  def getFakeWaitlistLength(handle: String) = UserAction { request =>
    Ok(Json.obj(
      "length" -> commander.getFakeWaitlistLength()
    ))
  }

  def requestToTwitterWaitlistRedirectHack = requestToTwitterWaitlist

  def requestToTwitterWaitlist = MaybeUserAction { implicit request =>
    val session = request.session
    request match {
      case requestNonUser: NonUserRequest[_] =>
        Redirect("/signup/twitter?intent=waitlist")
      case ur: UserRequest[_] =>
        val twitterSocialForUser = db.readOnlyMaster { implicit s =>
          socialRepo.getByUser(ur.user.id.get).find(_.networkType == SocialNetworks.TWITTER)
        }
        if (twitterSocialForUser.isDefined) {
          // if user already has twitter account, go to thanks
          Redirect("/twitter/thanks")
        } else {
          // if user does not have twitter account, link it!
          Redirect("/link/twitter").withSession(session + (SecureSocial.OriginalUrlKey -> "/twitter/thanks"))
        }
    }
  }

  def thanksForTwitterWaitlistRedirectHack = thanksForTwitterWaitlist

  def thanksForTwitterWaitlist = MaybeUserAction { implicit request =>
    val session = request.session
    request match {
      case requestNonUser: NonUserRequest[_] =>
        Redirect("/twitter/request")
      case ur: UserRequest[_] =>
        val (twitterSui, existingSync) = db.readOnlyMaster { implicit session =>
          val sui = socialRepo.getByUser(ur.userId).find { s =>
            s.networkType == SocialNetworks.TWITTER &&
              (s.state == SocialUserInfoStates.FETCHED_USING_SELF ||
                s.state == SocialUserInfoStates.CREATED)
          }
          val existingSync = twitterSyncStateRepo.getByUserIdUsed(ur.userId).sortBy(_.id).reverse.headOption
          (sui, existingSync)
        }
        if (twitterSui.isEmpty) {
          Redirect("/link/twitter?intent=waitlist").withSession(session + (SecureSocial.OriginalUrlKey -> "/twitter/thanks"))
        } else {
          commander.createSyncOrWaitlist(ur.userId, SyncTarget.Tweets) match {
            case Left(error) =>
              log.warn(s"[thanksForTwitterWaitlist] ${ur.userId} Error when creating sync, $error")
              db.readWrite { implicit s => userValueRepo.setValue(ur.userId, UserValueName.TWITTER_SYNC_PROMO, "show_sync") }
              MarketingSiteRouter.marketingSite("twitter-confirmation")
            case Right(Right(sync)) if syncIsReady(sync) =>
              log.info(s"[thanksForTwitterWaitlist] ${ur.userId} Had a sync, redirecting $sync")
              db.readWrite { implicit s => userValueRepo.clearValue(ur.userId, UserValueName.TWITTER_SYNC_PROMO) }
              redirectToLibrary(existingSync.get.libraryId)
            case Right(waitOrNewSync) =>
              log.info(s"[thanksForTwitterWaitlist] ${ur.userId} now on waitlist $waitOrNewSync")
              db.readWrite { implicit s => userValueRepo.setValue(ur.userId, UserValueName.TWITTER_SYNC_PROMO, "in_progress") }
              MarketingSiteRouter.marketingSite("twitter-confirmation")
          }
        }
    }
  }

  private def syncIsReady(sync: TwitterSyncState) = {
    db.readOnlyReplica { implicit session =>
      // Very old, or recent and has keeps
      sync.createdAt.isBefore(clock.now.minusMinutes(120)) ||
        (sync.createdAt.isBefore(clock.now.minusMinutes(2)) && libraryRepo.get(sync.libraryId).keepCount > 0)
    }
  }

  private def redirectToLibrary(libraryId: Id[Library]) = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    Redirect(libPathCommander.getPathForLibrary(library))
  }

  def createSync(target: Option[String]) = UserAction { request =>
    val syncTarget = target match {
      case Some(SyncTarget.Favorites.value) => SyncTarget.Favorites
      case _ => SyncTarget.Tweets
    }
    commander.createSyncOrWaitlist(request.userId, syncTarget) match {
      case Left(err) =>
        BadRequest(Json.obj("error" -> "failed", "msg" -> err))
      case Right(Left(wait)) =>
        Accepted(Json.obj("pending" -> true, "handle" -> wait.twitterHandle.map(_.value)))
      case Right(Right(sync)) =>
        val library = db.readOnlyReplica { implicit session =>
          libraryRepo.get(sync.libraryId)
        }
        if (library.keepCount > 0) {
          Ok(Json.obj("complete" -> true, "url" -> libPathCommander.getPathForLibrary(library), "handle" -> sync.twitterHandle.value))
        } else {
          Ok(Json.obj("complete" -> false, "url" -> libPathCommander.getPathForLibrary(library), "handle" -> sync.twitterHandle.value))
        }
    }
  }

}
