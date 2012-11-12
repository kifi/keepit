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
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.common.social._
import views.html.defaultpages.unauthorized


trait FortyTwoController extends Controller with Logging with SecureSocial {

  private val FORTYTWO_USER_ID = "fortytwo_user_id"
  
  def AdminAction(action: SecuredRequest[AnyContent] => PlainResult): Action[AnyContent] = {
    SecuredAction(false, parse.anyContent) { implicit request =>
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
      if (!experiments.contains(UserExperiment.ExperimentTypes.ADMIN)) {
        Unauthorized("User %s does not have an admin auth, flushing session... If you think you should see this page, please contact FortyTwo Engineering.".format(userId)).withNewSession
      } else {
        action(request).withSession(newSession)
      }
    }
  }
  
}