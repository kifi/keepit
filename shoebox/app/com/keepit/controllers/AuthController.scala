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
 *
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
import play.api.libs.json._
import com.keepit.common.logging.Logging
import com.keepit.model.SocialUserInfo
import com.keepit.model.User
import com.keepit.common.db.CX
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks
import com.keepit.common.logging.Logging

/**
 * The Login page controller
 */
object AuthController extends Controller with securesocial.core.SecureSocial with Logging
{
  def isLoggedIn = SecuredAction(true) { implicit request => {
	  UserService.find(request.user.id) match {
	    case None =>
		    Ok(JsObject(("status" -> JsString("loggedout")) :: Nil)) 
	    case Some(socialUser) => 
	      log.info("facebook id %s".format(socialUser.id.id))
	      val user = CX.withConnection { implicit c =>
  	    	val userId = SocialUserInfo.get(SocialId(socialUser.id.id), SocialNetworks.FACEBOOK).userId.get
  	    	User.get(userId)
  	  	}
  			Ok(JsObject(
  			  ("status" -> JsString("loggedin")) ::
  			  ("avatarUrl" -> JsString(socialUser.avatarUrl.get)) ::
  			  ("name" -> JsString(socialUser.displayName)) :: 
  			  ("facebookId" -> JsString(socialUser.id.id)) ::
  			  ("provider" -> JsString(socialUser.id.providerId)) ::
  			  ("externalId" -> JsString(user.externalId.id)) ::
  			  Nil)
        )
	    }
	  }
  }
  
  def welcome = SecuredAction() { implicit request =>
    log.debug("in welcome. with user : [ %s ]".format(request.user ))
    Redirect(com.keepit.controllers.routes.HomeController.home())
  }
}
