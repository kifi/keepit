package com.keepit.common.controller

import play.api.data._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.inject._
import com.keepit.common.net._
import com.keepit.common.db.{Id, CX, ExternalId, State}
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.common.social._
import views.html.defaultpages.unauthorized


trait FortyTwoController extends Controller with Logging with SecureSocial {

  private val FORTYTWO_USER_ID = "fortytwo_user_id"

  case class AuthenticatedRequest(socialUser: SocialUser, userId: Id[User], request: Request[AnyContent], experimants: Seq[State[UserExperiment.ExperimentType]])
    extends WrappedRequest(request)

  def AuthenticatedJsonAction(action: AuthenticatedRequest => Result): Action[AnyContent] = Action(parse.anyContent) { request =>
    AuthenticatedAction(true, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }

  def AuthenticatedHtmlAction(action: AuthenticatedRequest => Result): Action[AnyContent] =
    AuthenticatedAction(false, action)

  private[controller] def AuthenticatedAction[A](isApi: Boolean, action: AuthenticatedRequest => Result) = {
    SecuredAction(isApi, parse.anyContent) { implicit request =>
      val userIdOpt = request.session.get(FORTYTWO_USER_ID).map{id => Id[User](id.toLong)}
      val (userId, experiments, newSession) = CX.withConnection { implicit conn =>
        val (userId, newSession) = userIdOpt match {
          case None =>
            val socialUser = SocialUserInfo.get(SocialId(request.user.id.id), SocialNetworks.FACEBOOK)
            val userId = socialUser.userId.get
            (userId, session + (FORTYTWO_USER_ID -> userId.id.toString))
          case Some(userId) =>
            (userId, session)
        }
        val experiments = UserExperiment.getByUser(userId)
        (userId, experiments.map(_.experimentType), newSession)
      }
      if (experiments.contains(UserExperiment.ExperimentTypes.BLOCK)) {
        val message = "user %s access is forbidden".format(userId)
        log.warn(message)
        Forbidden(message)
      } else {
        action(AuthenticatedRequest(request.user, userId, request.request, experiments)) match {
          case r: PlainResult => r.withSession(newSession)
          case any => any
        }
      }
    }
  }

  def AdminJsonAction(action: AuthenticatedRequest => Result): Action[AnyContent] = Action(parse.anyContent) { request =>
    AdminAction(true, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }

  def AdminHtmlAction(action: AuthenticatedRequest => Result): Action[AnyContent] =
    AdminAction(false, action)

  private[controller] def AdminAction(isApi: Boolean, action: AuthenticatedRequest => Result): Action[AnyContent] = {
    AuthenticatedAction(isApi, { implicit request =>
      val isAdmin = CX.withConnection { implicit conn =>
        UserExperiment.getExperiment(request.userId, UserExperiment.ExperimentTypes.ADMIN).isDefined
      }
      val authorizedDevUser = Play.isDev && request.userId.id == 1L
      if (authorizedDevUser || isAdmin) {
        action(request)
      } else {
        Unauthorized("""User %s does not have admin auth in %s mode, flushing session...
            If you think you should see this page, please contact FortyTwo Engineering.""".format(request.userId, current.mode)).withNewSession
      }
    })
  }

}