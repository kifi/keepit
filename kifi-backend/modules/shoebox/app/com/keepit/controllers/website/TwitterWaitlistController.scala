package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.TwitterWaitlistCommander
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.social.SocialNetworks
import play.api.libs.json.Json
import scala.util.Try

class TwitterWaitlistController @Inject() (
    commander: TwitterWaitlistCommander,
    socialRepo: SocialUserInfoRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    db: Database,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  def twitterWaitlistLanding() = MaybeUserAction { implicit request =>
    MarketingSiteRouter.marketingSite("twitter-home")
  }

  //DO NOT USE THE WORD *FAKE* IN THE ROUTE FOR THIS!!!
  def getFakeWaitlistPosition() = UserAction { request =>
    val (handleOpt, emailOpt) = db.readOnlyReplica { implicit session =>
      val twOpt = socialRepo.getByUser(request.userId).find(_.networkType == SocialNetworks.TWITTER).flatMap {
        _.getProfileUrl.map(url => url.substring(url.lastIndexOf('/') + 1))
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

}
