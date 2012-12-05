package com.keepit.controllers

/**
 * Copyright 2012 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import play.api.mvc.{Action, Controller}
import play.api.i18n.Messages
import securesocial.core._
import play.api.{Play, Logger}
import Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.{CX, ExternalId}
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialId, SocialNetworks}
import com.keepit.common.logging.Logging
import com.keepit.model.{KifiInstallation, KifiVersion, SocialUserInfo, User, UserAgent}

object AuthController extends FortyTwoController {
  // TODO: remove when all beta users are on 2.0.2+
  def isLoggedIn = AuthenticatedJsonAction { implicit request =>
	  UserService.find(request.socialUser.id) match {
	    case None =>
		    Ok(JsObject(("status" -> JsString("loggedout")) :: Nil)).withNewSession
	    case Some(socialUser) =>
	      log.info("facebook id %s".format(socialUser.id.id))
	      val user = CX.withConnection { implicit c =>
  	    	val userId = SocialUserInfo.get(SocialId(socialUser.id.id), SocialNetworks.FACEBOOK).userId.get
  	    	User.get(userId)
  	  	}
        Ok(JsObject(Seq(
          "status" -> JsString("loggedin"),
          "avatarUrl" -> JsString(socialUser.avatarUrl.get),
          "name" -> JsString(socialUser.displayName),
          "facebookId" -> JsString(socialUser.id.id),
          "provider" -> JsString(socialUser.id.providerId),
          "externalId" -> JsString(user.externalId.id))))
    }
  }

  def start = AuthenticatedJsonAction { implicit request =>
    val socialUser = request.socialUser
    log.info("facebook id %s".format(socialUser.id))
    val params = request.body.asFormUrlEncoded.get
    val (user, installation) = CX.withConnection { implicit c =>
      val userAgent = UserAgent(params.get("agent").get.head)
      val version = KifiVersion(params.get("version").get.head)
      val installationOpt: Option[String] = params.get("installation").flatMap(s=>s.headOption)

      log.info("start. details: %s, %s, %s".format(userAgent, version, installationOpt))
      val installation: KifiInstallation = installationOpt flatMap { id =>
        KifiInstallation.getOpt(request.userId, ExternalId[KifiInstallation](id))
      } match {
        case None =>
          KifiInstallation(userId = request.userId, userAgent = userAgent, version = version).save
        case Some(install) =>
          if (install.version != version || install.userAgent != userAgent) {
            install.withUserAgent(userAgent).withVersion(version).save
          } else {
            install
          }
      }

      (User.get(request.userId), installation)
    }

    Ok(JsObject(Seq(
      "avatarUrl" -> JsString(socialUser.avatarUrl.get),
      "name" -> JsString(socialUser.displayName),
      "facebookId" -> JsString(socialUser.id.id),
      "provider" -> JsString(socialUser.id.providerId),
      "userId" -> JsString(user.externalId.id),
      "installationId" -> JsString(installation.externalId.id))))
  }

  // where SecureSocial sends users if it can't figure out the right place (see securesocial.conf)
  def welcome = SecuredAction() { implicit request =>
    log.debug("in welcome. with user : [ %s ]".format(request.user ))
    Redirect(com.keepit.controllers.routes.HomeController.home())
  }
}
