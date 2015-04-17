package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.TwitterWaitlistCommander
import com.keepit.common.controller._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.model._
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
    db: Database,
    airbrakeNotifier: AirbrakeNotifier,
    socialGraphPlugin: SocialGraphPlugin,
    val userActionsHelper: UserActionsHelper,
    implicit val ec: ExecutionContext) extends UserActions with ShoeboxServiceController {

  def twitterWaitlistLanding() = MaybeUserAction { implicit request =>
    MarketingSiteRouter.marketingSite("twitter-home")
  }

  //DO NOT USE THE WORD *FAKE* IN THE ROUTE FOR THIS!!!
  def getFakeWaitlistPosition() = UserAction { request =>
    val (handleOpt, emailOpt) = db.readOnlyReplica { implicit session =>
      val twOpt = socialRepo.getByUser(request.userId).find(_.networkType == SocialNetworks.TWITTER).flatMap { info =>
        info.username.orElse(info.getProfileUrl.map(url => url.substring(url.lastIndexOf('/') + 1)))
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

  def requestToTwitterWaitlist = MaybeUserAction { implicit request =>
    val session = request.session
    request match {
      case requestNonUser: NonUserRequest[_] =>
        Redirect("/signup/twitter?intent=waitlist").withSession(session + (SecureSocial.OriginalUrlKey -> "/twitter/thanks"))
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

  private def pollDbForTwitterHandle(userId: Id[User], iterations: Int): Future[Option[String]] = {
    /*
     * This is not good code, but appears to be necessary because initially, when a social
     * account is brought in, the SocialUserInfo record is not complete. Namely, it's missing
     * the profileUrl field. Only after our system syncs with the social network do we get this.
     * Unfortunately, in this code path, it's nearly always too fast, so it's empty. However,
     * we need the handle here. So we poll the db every 600 ms (up to `iterations` times) looking for it.
     * We aggressively log if we can't get it fast enough.
     *
     * What we should do:
     *   1) Handle creation of SocialUserInfo records ourselves, filling them to our heart's desire.
     *   2) Minimize dependency on SecureSocial's static approach. It won't work much longer.
     */
    def checkStatusOfTwitterUser() = {
      db.readOnlyMaster { implicit session =>
        socialRepo.getByUsers(Seq(userId)).find(_.networkType == SocialNetworks.TWITTER).flatMap { tsui =>
          if (tsui.state == SocialUserInfoStates.CREATED) {
            log.info(s"[checkStatusOfTwitterUser] Still waiting on ${tsui.networkType}/${tsui.socialId}")
            None // pending sync, keep polling
          } else if (tsui.state == SocialUserInfoStates.FETCHED_USING_SELF && tsui.getProfileUrl.isDefined) { // done
            log.info(s"[checkStatusOfTwitterUser] Got it for ${tsui.networkType}/${tsui.socialId}")
            Some(Right(tsui.getProfileUrl.map(url => url.substring(url.lastIndexOf('/') + 1)).get))
          } else { // other
            log.info(s"[checkStatusOfTwitterUser] Couldn't get handle of ${tsui.networkType}/${tsui.socialId}")
            Some(Left(()))
          }
        }
      }
    }

    var times = 0
    def timeoutF = play.api.libs.concurrent.Promise.timeout(None, 600)
    def pollCheck(): Future[Option[String]] = {
      timeoutF.flatMap { _ =>
        checkStatusOfTwitterUser() match {
          case None if times < iterations =>
            times += 1
            pollCheck()
          case None | Some(Left(_)) => // Timed out or weird error
            Future.successful(None)
          case Some(Right(res)) => // Got handle!
            Future.successful(Some(res))
        }
      }
    }
    pollCheck()
  }

  def thanksForTwitterWaitlist = MaybeUserAction { implicit request =>
    val session = request.session
    request match {
      case requestNonUser: NonUserRequest[_] =>
        Redirect("/twitter/request")
      case ur: UserRequest[_] =>
        val twitterSui = db.readOnlyMaster { implicit session =>
          socialRepo.getByUser(ur.userId).find { s =>
            s.networkType == SocialNetworks.TWITTER &&
              (s.state == SocialUserInfoStates.FETCHED_USING_SELF ||
                s.state == SocialUserInfoStates.CREATED)
          }
        }
        if (twitterSui.isEmpty) {
          Redirect("/link/twitter?intent=waitlist").withSession(session + (SecureSocial.OriginalUrlKey -> "/twitter/thanks"))
        } else {
          pollDbForTwitterHandle(ur.userId, iterations = 60).map { twRes =>
            twRes match {
              case Some(handle) =>
                commander.addEntry(ur.userId, handle)
              case None => // we failed :(
                log.warn(s"Couldn't get twitter handle in time, we'll try again. userId: ${ur.userId.id}. They want to be waitlisted.")
                socialGraphPlugin.asyncFetch(twitterSui.get).onComplete { _ =>
                  pollDbForTwitterHandle(ur.userId, iterations = 60).onComplete {
                    case Success(Some(handle)) =>
                      commander.addEntry(ur.userId, handle)
                    case fail => // we failed :(
                      airbrakeNotifier.notify(s"Couldn't get twitter handle in time, failed retry. userId: ${ur.userId.id}. They want to be waitlisted. $fail")
                  }
                }
            }
          }
          MarketingSiteRouter.marketingSite("twitter-confirmation")
        }
    }
  }

}
