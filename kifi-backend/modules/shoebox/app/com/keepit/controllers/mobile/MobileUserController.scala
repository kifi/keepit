package com.keepit.controllers.mobile

import com.keepit.common.controller.{ ShoeboxServiceController, MobileController, ActionAuthenticator, AuthenticatedRequest }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.heimdal.{ DelightedAnswerSources, BasicDelightedAnswer }
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.commanders._

import play.api.Play.current
import play.api.libs.json._
import play.api.libs.json.Json.toJson

import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.util.{ Success, Failure }
import securesocial.core.{ SecureSocial, Authenticator }
import play.api.libs.json.JsSuccess
import com.keepit.common.controller.AuthenticatedRequest
import scala.util.Failure
import scala.Some
import com.keepit.commanders.ConnectionInfo
import scala.util.Success
import play.api.libs.json.JsObject
import com.keepit.common.http._

class MobileUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  userCommander: UserCommander,
  typeaheadCommander: TypeaheadCommander)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def friends(page: Int, pageSize: Int) = JsonAction.authenticated { request =>
    val (connectionsPage, total) = userCommander.getConnectionsPage(request.userId, page, pageSize)
    val friendsJsons = connectionsPage.map {
      case ConnectionInfo(friend, _, unfriended, unsearched) =>
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
    val json: JsValue = request.body
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
    request.body.validate[UpdatableUserInfo] match {
      case JsSuccess(userData, _) => {
        userData.emails.foreach(userCommander.updateEmailAddresses(request.userId, request.user.firstName, request.user.primaryEmail, _))
        userData.description.foreach { description =>
          userCommander.updateUserDescription(request.userId, description)
        }
        getUserInfo(request)
      }
      case JsError(errors) if errors.exists { case (path, _) => path == __ \ "emails" } => BadRequest(Json.obj("error" -> "bad email addresses"))
      case _ => BadRequest(Json.obj("error" -> "could not parse user info from body"))
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
        case Failure(e) => Forbidden(Json.obj("code" -> e.getMessage))
        case Success(_) => Ok(Json.obj("code" -> "password_changed"))
      }
    }
  }

  // legacy
  def queryAll(search: Option[String], network: Option[String], limit: Int, pictureUrl: Boolean = false) = JsonAction.authenticatedAsync { request =>
    typeaheadCommander.queryAll(request.userId, search, network, limit, pictureUrl) map { r =>
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

  def ignoreFriendRequest(externalId: ExternalId[User]) = JsonAction.authenticated { request =>
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

  def postDelightedAnswer = JsonAction.authenticatedParseJsonAsync { request =>
    implicit val source = DelightedAnswerSources.fromUserAgent(request.userAgentOpt)
    Json.fromJson[BasicDelightedAnswer](request.body) map { answer =>
      userCommander.postDelightedAnswer(request.userId, answer) map { success =>
        if (success) Ok else BadRequest
      }
    } getOrElse Future.successful(BadRequest)
  }

  def cancelDelightedSurvey = JsonAction.authenticated { implicit request =>
    // todo(martin) implement
    Ok
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

  private val MobilePrefNames = Set("show_delighted_question")

  def getPrefs() = JsonAction.authenticatedAsync { request =>
    // Make sure the user's last active date has been updated before returning the result
    userCommander.setLastUserActive(request.userId) map { _ =>
      Ok(userCommander.getPrefs(MobilePrefNames, request.userId))
    }
  }
}

