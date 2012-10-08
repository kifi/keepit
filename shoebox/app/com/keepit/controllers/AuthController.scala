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
import com.keepit.model.User
import com.keepit.model.FacebookId
import com.keepit.common.db.CX

/**
 * The Login page controller
 */
object AuthController extends Controller with securesocial.core.SecureSocial
{
  def isLoggedIn = SecuredAction(true) { implicit request => {
	  def socialUser = UserService.find(request.user.id)
      
	  println("facebook id %s".format(socialUser.get.id.id))
	  
	  if (socialUser==None) 
		  Ok(JsObject(("status" -> JsString("loggedout")) :: Nil)) 
	  else
	  {
	    var keepitId = "";
	  	CX.withConnection { implicit c =>
	    	val user = User.getOpt(FacebookId(socialUser.get.id.id))
	    	if(user!=None) keepitId = user.get.id.get.id.toString()
	  	}
			Ok(JsObject(
			  ("status" -> JsString("loggedin")) ::
			  ("avatarUrl" -> JsString(socialUser.get.avatarUrl.get)) ::
			  ("name" -> JsString(socialUser.get.displayName)) :: 
			  ("facebookId" -> JsString(socialUser.get.id.id)) ::
			  ("provider" -> JsString(socialUser.get.id.providerId)) ::
			  ("keepitId" -> JsString(keepitId)) ::
			  Nil)
			)
		}
  }}

  
  def welcome = SecuredAction() { implicit request =>
    Ok(securesocial.views.html.protectedAction(request.user))
  }
}
