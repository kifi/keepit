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
    /*val (user, bookmarks, socialUserInfos, socialConnections, fortyTwoConnections) = CX.withConnection { implicit c =>
      val userWithSocial = UserWithSocial.toUserWithSocial(User.get(userId)) 
      val bookmarks = Bookmark.ofUser(userWithSocial.user)
      val socialUserInfos = SocialUserInfo.getByUser(userWithSocial.user.id.get)
      val socialConnections = SocialConnection.getUserConnections(userId)
      val fortyTwoConnections = SocialConnection.getFortyTwoUserConnections(userId) map (User.get(_)) map UserWithSocial.toUserWithSocial

      
      (userWithSocial, bookmarks, socialUserInfos, socialConnections, fortyTwoConnections)
    }
    val rawInfos = socialUserInfos map {info =>
      inject[SocialUserRawInfoStore].get(info.id.get)
    } */
    
    val (socialUserInfo, socialConnections) = CX.withConnection { implicit conn =>
      val socialUserInfo = SocialUserInfo.get(socialUserId)
      val socialConnections = SocialConnection.getSocialUserConnections(socialUserId)
      
      (socialUserInfo, socialConnections)
    }
    
    val rawInfo = inject[SocialUserRawInfoStore].get(socialUserInfo.id.get)

    Ok(views.html.socialUser(socialUserInfo, socialConnections, rawInfo))
  }

  def socialUsersView = SecuredAction(false) { implicit request => 
    val users = CX.withConnection { implicit c => User.all map UserWithSocial.toUserWithSocial}
    Ok(views.html.users(users))
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
