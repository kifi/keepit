package com.keepit.controllers.ext

import play.api.mvc.Action
import play.api.mvc.WebSocket
import play.api.libs.json._
import play.api.mvc.Controller
import com.keepit.inject._
import com.keepit.common.controller.FortyTwoController
import com.keepit.realtime._
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import com.keepit.common.db._
import com.keepit.model.User
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.google.inject.{Inject, Singleton, ImplementedBy}
import securesocial.core.SecureSocial
import securesocial.core.UserService
import securesocial.core.UserId
import com.keepit.model.KifiInstallation
import com.keepit.common.controller.FortyTwoController._
import com.keepit.common.social.SocialId
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.social.SocialNetworks
import com.keepit.common.db.slick.Database
import play.api.mvc.RequestHeader
import com.keepit.common.controller.BrowserExtensionController

case class StreamSession(userId: Id[User], socialUser: SocialUserInfo, experiments: Seq[State[ExperimentType]], adminUserId: Option[Id[User]])

@Singleton
class ExtStreamController @Inject() (
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    experimentRepo: UserExperimentRepo,
    streamProvider: UserStreamProvider,
    streams: Streams) extends BrowserExtensionController with Logging {
  private def authenticate(request: RequestHeader): Option[StreamSession] = {
    /*
     * Unfortunately, everything related to existing secured actions intimately deals with Action, Request, Result, etc.
     * WebSockets cannot use these, so I've implemented what I need below.
     */
    for (
      userId <- request.session.get(SecureSocial.UserKey);
      providerId <- request.session.get(SecureSocial.ProviderKey);
      secSocialUser <- UserService.find(UserId(userId, providerId))
    ) yield {
      //val userIdOpt = request.session.get(FORTYTWO_USER_ID).map{id => Id[User](id.toLong)}
      val impersonatedUserIdOpt: Option[ExternalId[User]] = ImpersonateCookie.decodeFromCookie(request.cookies.get(ImpersonateCookie.COOKIE_NAME))

      db.readOnly { implicit session =>
        val socialUser = socialUserInfoRepo.get(SocialId(secSocialUser.id.id), SocialNetworks.FACEBOOK)
        val userId = socialUser.userId.get
        val experiments = experimentRepo.getUserExperiments(userId)
        impersonatedUserIdOpt match {
          case Some(impExtUserId) if experiments.find(e => e == ExperimentTypes.ADMIN).isDefined =>
            val impUserId =  userRepo.get(impExtUserId).id.get
            val impSocUserInfo = socialUserInfoRepo.getByUser(impUserId)
            StreamSession(impUserId, impSocUserInfo.head, experiments, Some(userId))
          case _ =>
            StreamSession(userId, socialUser, experiments, None)
        }
      }
    }
  }

  def ws() = WebSocket.using[JsValue] { implicit request  =>
    authenticate(request) match {
      case Some(streamSession) =>

        val feeds = streamProvider.getStreams(request.queryString.keys.toSeq)

        val enumerator = Enumerator.interleave(feeds.map(_.connect(streamSession.userId))).asInstanceOf[Enumerator[JsValue]]
        val iteratee = Iteratee.foreach[JsValue]{ message =>
          handleIncomingMessage(streamSession, message)
        }.mapDone { _ =>
          log.info(s"Client ${streamSession.userId} disconnecting!")
          feeds.map(_.disconnect(streamSession.userId))
        }

        (iteratee, enumerator)

      case None =>
        log.info(s"Anonymous user trying to connect. Disconnecting!")
        val enumerator: Enumerator[JsValue] = Enumerator(Json.obj("error" -> "Permission denied. Are you logged in? Connect again to re-authenticate."))
        val iteratee = Iteratee.ignore[JsValue]

        (iteratee, enumerator >>> Enumerator.eof)
    }
  }

  def handleIncomingMessage(streamSession: StreamSession, message: JsValue) = {
    log.info(s"New message from ${streamSession.userId}: $message")
    // And handle here...
  }

}
