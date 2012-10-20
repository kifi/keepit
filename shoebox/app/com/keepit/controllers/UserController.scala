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

object UserController extends Controller with Logging with SecureSocial {

    /**
   * Call me using:
   * curl localhost:9000/users/keepurl?url=http://www.ynet.co.il/;echo
   */
  def usersKeptUrl(url: String) = Action { request =>    
    val users = CX.withConnection { implicit c =>
      val nuri = NormalizedURI("title", url)
      log.info("userWhoKeptUrl %s (hash=%s)".format(url, nuri.urlHash))
      User.getbyUrlHash(nuri.urlHash) map UserWithSocial.toUserWithSocial
    }
    Ok(userWithSocialSerializer.writes(users)).as(ContentTypes.JSON)
  }
  
  /**
   * Call me using:
   * $ curl localhost:9000/admin/user/get/all | python -mjson.tool
   */
  def getUsers = Action { request =>
    val users = CX.withConnection { implicit c =>
      User.all map UserWithSocial.toUserWithSocial
    }
    Ok(JsArray(users map { user => 
      JsObject(List(
        "userId" -> JsNumber(user.user.id.get.id),//deprecated, lets stop exposing user id to the outside world. use external id instead.
        "externalId" -> JsString(user.user.externalId.id),
        "userObject" -> userWithSocialSerializer.writes(user)
      ))
    }))
  }

  def getUser(id: Id[User]) = Action { request =>
    val user = CX.withConnection { implicit c =>
      UserWithSocial.toUserWithSocial(User.get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }

  def getUserByExternal(id: ExternalId[User]) = Action { request =>
    val user = CX.withConnection { implicit c =>
      UserWithSocial.toUserWithSocial(User.get(id))
    }
    Ok(userWithSocialSerializer.writes(user))
  }

  def userView(userId: Id[User]) = SecuredAction(false) { implicit request => 
    val (user, bookmarks, socialUserInfos) = CX.withConnection { implicit c =>
      val userWithSocial = UserWithSocial.toUserWithSocial(User.get(userId)) 
      val bookmarks = Bookmark.ofUser(userWithSocial.user)
      val socialUserInfos = SocialUserInfo.getByUser(userWithSocial.user.id.get)
      (userWithSocial, bookmarks, socialUserInfos)
    }
    Ok(views.html.user(user, bookmarks, socialUserInfos))
  }

  def usersView = SecuredAction(false) { implicit request => 
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
