package com.keepit.controllers.mobile

import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator, AuthenticatedRequest}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.commanders._

import play.api.Play.current
import play.api.libs.json.{JsObject, Json, JsValue}
import play.api.libs.json.Json.toJson

import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.{Success, Failure}
import securesocial.core.{SecureSocial, Authenticator}

class MobileUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  userCommander: UserCommander,
  typeaheadCommander: TypeaheadCommander)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def friends(page: Int, pageSize: Int) = JsonAction.authenticated { request =>
    val (connectionsPage, total) = userCommander.getConnectionsPage(request.userId, page, pageSize)
    val friendsJsons = connectionsPage.map { case ConnectionInfo(friend, _, unfriended, unsearched) =>
      Json.toJson(friend).asInstanceOf[JsObject] ++ Json.obj(
        "searchFriend" -> unsearched,
        "unfriended" -> unfriended
      )
    }
    Ok(Json.obj(
      "friends" -> friendsJsons,
      "total" -> total
    ))
  }

  def socialNetworkInfo() = JsonAction.authenticated { request =>
    Ok(Json.toJson(userCommander.socialNetworkInfo(request.userId)))
  }

  def abookInfo() = JsonAction.authenticatedAsync { request =>
    userCommander.abookInfo(request.userId) map { abooks =>
      Ok(Json.toJson(abooks))
    }
  }

  def uploadContacts(origin: ABookOriginType) = JsonAction.authenticatedAsync(parse.json(maxLength = 1024 * 50000)) { request =>
    val json : JsValue = request.body
    userCommander.uploadContactsProxy(request.userId, origin, json) map { abookInfoTr =>
      abookInfoTr match {
        case Success(abookInfo) => Ok(Json.toJson(abookInfo))
        case Failure(ex) => BadRequest(Json.obj("code" -> ex.getMessage)) // can do better
      }
    }
  }

  def currentUser = JsonAction.authenticated(allowPending = true) { implicit request =>
    getUserInfo(request)
  }

  def updateCurrentUser = JsonAction.authenticatedParseJson(allowPending = true) { implicit request =>
    request.body.asOpt[UpdatableUserInfo] map { userData =>
      if (userData.emails.isDefined && !userCommander.validateEmails(userData.emails.get:_*)) {
        BadRequest(Json.obj("error" -> "bad email addresses"))
      } else {
        userData.emails.foreach(userCommander.updateEmailAddresses(request.userId, request.user.firstName, request.user.primaryEmail, _))
        userData.description.foreach{ description =>
          userCommander.updateUserDescription(request.userId, description)
        }
        getUserInfo(request)
      }
    } getOrElse {
      BadRequest(Json.obj("error" -> "could not parse user info from body"))
    }
  }

  private def getUserInfo[T](request: AuthenticatedRequest[T]) = {
    val user = userCommander.getUserInfo(request.user)
    val (clickCount, rekeepCount, rekeepTotalCount) = userCommander.getKeepAttributionCounts(request.userId)
    Ok(toJson(user.basicUser).as[JsObject] ++
       toJson(user.info).as[JsObject] ++
       Json.obj(
         "notAuthed" -> user.notAuthed,
         "experiments" -> request.experiments.map(_.value),
         "clickCount" -> clickCount,
         "rekeepCount" -> rekeepCount,
         "rekeepTotalCount" -> rekeepTotalCount
       )
    )
  }

  def changePassword = JsonAction.authenticatedParseJson(allowPending = true) { implicit request =>
    val oldPassword = (request.body \ "oldPassword").as[String] // todo: use char[]
    val newPassword = (request.body \ "newPassword").as[String]
    if (newPassword.length < 7) {
      BadRequest(Json.obj("error" -> "bad_new_password"))
    } else {
      userCommander.doChangePassword(request.userId, oldPassword, newPassword) match {
        case Failure(e)  => Forbidden(Json.obj("code" -> e.getMessage))
        case Success(_) => Ok(Json.obj("code" -> "password_changed"))
      }
    }
  }

  // legacy
  def queryAll(search: Option[String], network: Option[String], limit: Int, pictureUrl:Boolean = false) = JsonAction.authenticatedAsync { request =>
    typeaheadCommander.queryAll(request.userId, search, network, limit, pictureUrl) map { r =>
      Ok(Json.toJson(r))
    }
  }

  def querySocial(search: Option[String], network: Option[String], limit: Int, pictureUrl:Boolean = false) = JsonAction.authenticated { request =>
    Ok(Json.toJson(typeaheadCommander.querySocialInviteStatus(request.userId, search, network, limit, pictureUrl)))
  }

  def queryContacts(search: Option[String], limit: Int, pictureUrl:Boolean = false) = JsonAction.authenticatedAsync { request =>
    typeaheadCommander.queryContactsInviteStatus(request.userId, search, limit) map { r =>
      Ok(Json.toJson(r))
    }
  }

  def friend(externalId: ExternalId[User]) = JsonAction.authenticated { request =>
    val (success, code) = userCommander.friend(request.userId, externalId)
    val res = Json.obj("code" -> code)
    if (success) Ok(res) else NotFound(res)
  }

  def unfriend(externalId: ExternalId[User]) = JsonAction.authenticated { request =>
    if (userCommander.unfriend(request.userId, externalId)) {
      Ok(Json.obj("code" -> "removed"))
    } else {
      NotFound(Json.obj("code" -> "user_not_found"))
    }
  }

  def ignoreFriendRequest(externalId:ExternalId[User]) = JsonAction.authenticated { request =>
    val (success, code) = userCommander.ignoreFriendRequest(request.userId, externalId)
    val res = Json.obj("code" -> code)
    if (success) Ok(res) else NotFound(res)
  }

  def incomingFriendRequests = JsonAction.authenticated { request =>
    val users = userCommander.incomingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def outgoingFriendRequests = JsonAction.authenticated { request =>
    val users = userCommander.outgoingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def disconnect(networkString: String) = JsonAction.authenticated(parser = parse.anyContent) { implicit request =>
    val (suiOpt, code) = userCommander.disconnect(request.userId, networkString)
    suiOpt match {
      case None => BadRequest(Json.obj("code" -> code))
      case Some(newLoginUser) =>
        val identity = newLoginUser.credentials.get
        Authenticator.create(identity).fold(
          error => Status(INTERNAL_SERVER_ERROR)(Json.obj("code" -> "internal_server_error")),
          authenticator => {
            Ok(Json.obj("code" -> code))
              .withSession(session - SecureSocial.OriginalUrlKey + (ActionAuthenticator.FORTYTWO_USER_ID -> newLoginUser.userId.get.toString)) // note: newLoginuser.userId
              .withCookies(authenticator.toCookie)
          }
        )
    }
  }

  def excludeFriend(id: ExternalId[User]) = JsonAction.authenticated { request =>
    userCommander.excludeFriend(request.userId, id) map { changed =>
      val msg = if (changed) "changed" else "no_change"
      Ok(Json.obj("code" -> msg))
    } getOrElse {
      BadRequest(Json.obj("code" -> "not_friend"))
    }
  }

  def includeFriend(id: ExternalId[User]) = JsonAction.authenticated { request =>
    userCommander.includeFriend(request.userId, id) map { changed =>
      val msg = if (changed) "changed" else "no_change"
      Ok(Json.obj("code" -> msg))
    } getOrElse {
      BadRequest(Json.obj("code" -> "not_friend"))
    }
  }

}


