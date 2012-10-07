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
  /**
   * The property that specifies the page the user is redirected to if there is no original URL saved in
   * the session.
   */
  val onLoginGoTo = "securesocial.onLoginGoTo"

  /**
   * The property that specifies the page the user is redirected to after logging out.
   */
  val onLogoutGoTo = "securesocial.onLogoutGoTo"

  /**
   * The root path
   */
  val Root = "/"



  /**
   * Renders the login page
   * @return
   */
  def login = Action { implicit request =>
    Ok(securesocial.views.html.login(ProviderRegistry.all().values, securesocial.core.providers.UsernamePasswordProvider.loginForm))
  }

  /**
   * Logs out the user by clearing the credentials from the session.
   * The browser is redirected either to the login page or to the page specified in the onLogoutGoTo property.
   *
   * @return
   */
  def logout = Action { implicit request =>
    val to = Play.configuration.getString(onLogoutGoTo).getOrElse(routes.AuthController.login().absoluteURL())
    Redirect(to).withSession(session - SecureSocial.UserKey - SecureSocial.ProviderKey)
  }

  /**
   * The authentication flow for all providers starts here.
   *
   * @param provider The id of the provider that needs to handle the call
   * @return
   */
  def authenticate(provider: String) = handleAuth(provider)
  def authenticateByPost(provider: String) = handleAuth(provider)

  
  private def handleAuth(provider: String) = Action { implicit request =>
    ProviderRegistry.get(provider) match {
      case Some(p) => {
        try {
          p.authenticate().fold( result => result , {
            user =>
              if ( Logger.isDebugEnabled ) {
                Logger.debug("User logged in : [" + user + "]")
                
              }
              CX.withConnection { implicit c =>
              	val keepitId = internUser(FacebookId(user.id.id),user.displayName, user.displayName).id 
              	Logger.debug("intern user with facebookId %s, keepitId = %s".format(user.id.id, keepitId))
              }
              
              val toUrl = session.get(SecureSocial.OriginalUrlKey).getOrElse(
                Play.configuration.getString(onLoginGoTo).getOrElse("/welcome")
              )
              Redirect(toUrl).withSession { session +
                (SecureSocial.UserKey -> user.id.id) +
                (SecureSocial.ProviderKey -> user.id.providerId) -
                SecureSocial.OriginalUrlKey
              }
          })
        } catch {
          case ex: AccessDeniedException => Logger.warn("User declined access using provider " + provider)
          Redirect(routes.AuthController.login()).flashing("error" -> Messages("securesocial.login.accessDenied"))
        }
      }
      case _ => NotFound
    }
  }
  
 
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
    Ok(views.html.welcome(request.user))
  }
  
  private def internUser(facebookId: FacebookId, firstName : String, lastName : String): User = CX.withConnection { implicit conn =>     
  	User.getOpt(facebookId) match {
 			case Some(user) => 
 				user
 			case None =>
 				User(
 					firstName = firstName,
 					lastName = lastName,
 					facebookId = Some(facebookId)
 				).save
  	}
  }
}
