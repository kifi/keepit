package com.keepit.controllers

import play.api.data._
import java.util.concurrent.TimeUnit
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
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.model.FacebookSession
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.model._
import com.keepit.serializer.UserSerializer._
import com.keepit.serializer.UserSerializer
import com.keepit.controllers.CommonActions._
import com.keepit.model.FacebookId
import play.api.http.ContentTypes
import securesocial.core._

object UserController extends Controller with Logging with SecureSocial {

  /**
   * Call me using:
   * curl -d '{"firstName":"Joe","lastName":"Smith"}' localhost:9000/admin/user/create;echo
   */
  def createUser = JsonAction { request =>
    val json: JsValue = request.body
    val user = CX.withConnection { implicit c =>
      val firstName = (json \ "firstName").as[String]
      val lastName = (json \ "lastName").as[String]
      val externalId = (json \ "externalId").asOpt[String].map(ExternalId[User](_))
      val facebookId = (json \ "facebookId").as[String]
      var user = User(firstName = firstName, lastName = lastName, facebookId = FacebookId(facebookId))
      user = externalId match {
        case Some(externalId) => user.withExternalId(externalId)
        case None => user
      }
      user = user.save
      user
    }
    Ok(JsObject(List(
      "userId" -> JsNumber(user.id.get.id),//deprecated, lets stop exposing user id to the outside world. use external id instead.
      "externalId" -> JsString(user.externalId.id),
      "userObject" -> UserSerializer.userSerializer.writes(user)
    )))
  }

    /**
   * Call me using:
   * curl localhost:9000/users/keepurl?url=http://www.ynet.co.il/;echo
   */
  def usersKeptUrl(url: String) = Action { request =>    
    val users = CX.withConnection { implicit c =>
      val nuri = NormalizedURI("title", url)
      log.info("userWhoKeptUrl %s (hash=%s)".format(url, nuri.urlHash))
      User.getbyUrlHash(nuri.urlHash)
    }
    Ok(UserSerializer.userSerializer.writes(users)).as(ContentTypes.JSON)
  }

  /**
   * Call me using:
   * $ curl localhost:9000/admin/user/get/all | python -mjson.tool
   */
  def getUsers = Action { request =>
    val users = CX.withConnection { implicit c =>
      User.all
    }
    Ok(JsArray(users map { user => 
      JsObject(List(
        "userId" -> JsNumber(user.id.get.id),//deprecated, lets stop exposing user id to the outside world. use external id instead.
      "externalId" -> JsString(user.externalId.id),
        "userObject" -> UserSerializer.userSerializer.writes(user)
      ))
    }))
  }

  def getUser(id: Id[User]) = Action { request =>
    val user = CX.withConnection { implicit c =>
      User.get(id)
    }
    Ok(UserSerializer.userSerializer.writes(user))
  }

  def getUserByExternal(id: ExternalId[User]) = Action { request =>
    val user = CX.withConnection { implicit c =>
      User.get(id)
    }
    Ok(UserSerializer.userSerializer.writes(user))
  }

  def userView(userId: Id[User]) = SecuredAction(false) { implicit request => 
    val (user, bookmarks) = CX.withConnection { implicit c =>
      val user = User.get(userId)
      val bookmarks = Bookmark.ofUser(user)
      (user, bookmarks)
    }
    Ok(views.html.user(user, bookmarks))
  }

  def usersView = SecuredAction(false) { implicit request => 
    val users = CX.withConnection { implicit c => User.all }
    Ok(views.html.users(users))
  }
}

