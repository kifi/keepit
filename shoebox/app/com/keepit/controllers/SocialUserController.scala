package com.keepit.controllers

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
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.UserWithSocialSerializer
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.social.{SocialGraphPlugin, UserWithSocial}
import com.keepit.common.social.SocialUserRawInfoStore

object SocialUserController extends Controller with Logging with SecureSocial {

  def socialUserView(socialUserId: Id[SocialUserInfo]) = SecuredAction(false) { implicit request => 
    
    val (socialUserInfo, socialConnections) = CX.withConnection { implicit conn =>
      val socialUserInfo = SocialUserInfo.get(socialUserId)
      val socialConnections = SocialConnection.getSocialUserConnections(socialUserId).sortWith((a,b) => a.fullName < b.fullName)
      
      (socialUserInfo, socialConnections)
    }
    
    val rawInfo = inject[SocialUserRawInfoStore].get(socialUserInfo.id.get)

    Ok(views.html.socialUser(socialUserInfo, socialConnections, rawInfo))
  }

  def socialUsersView = SecuredAction(false) { implicit request => 
    val socialUsers = CX.withConnection { implicit c => SocialUserInfo.all }
    Ok(views.html.socialUsers(socialUsers))
  }
  
  def refreshSocialInfo(userId: Id[User]) = SecuredAction(false) { implicit request => 
    val socialUserInfos = CX.withConnection { implicit c => 
      val user = User.get(userId)
      SocialUserInfo.getByUser(user.id.get)
    }
    val graph = inject[SocialGraphPlugin]
    socialUserInfos foreach { info =>
      graph.asyncFetch(info)
    }
    Redirect(com.keepit.controllers.routes.UserController.userView(userId))
  }
}
