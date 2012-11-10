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

  def AdminAction(action: SecuredRequest[AnyContent] => Result): Action[AnyContent] = {
    SecuredAction(false, parse.anyContent) { implicit request =>
      val (socialUser, experiments) = CX.withConnection { implicit conn =>
        val socialUser = SocialUserInfo.get(SocialId(request.user.id.id), SocialNetworks.FACEBOOK)
        val experiments = UserExperiment.getByUser(socialUser.userId.get)
        (socialUser, experiments.map(_.experimentType))
      }
      if (!experiments.contains(UserExperiment.ExperimentTypes.ADMIN)) {
        Unauthorized("Social user %s does not have an admin auth".format(socialUser.socialId.id))
      } else {
        action(request)
      }
    }
  }
  
}