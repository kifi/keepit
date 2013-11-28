package com.keepit.controllers.mobile

import com.keepit.classify.{Domain, DomainRepo, DomainStates}
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
import com.keepit.common.net.URI
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobileUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  userCommander: UserCommander)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def getFriends() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(userCommander.getFriends(request.user, request.experiments)))
  }

  def socialNetworkInfo() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(userCommander.socialNetworkInfo(request.userId)))
  }

  def uploadContacts(origin: ABookOriginType) = AuthenticatedJsonAction(parse.json(maxLength = 1024 * 50000)) { request =>
    val json : JsValue = request.body
    Async{
      userCommander.uploadContactsProxy(request.userId, origin, json).map(Ok(_))
    }
  }

  def currentUser = AuthenticatedJsonAction(true) { implicit request =>
    Async {
      getUserInfo(request)
    }
  }

  private def getUserInfo[T](request: AuthenticatedRequest[T]) = {
    userCommander.getUserInfo(request.user) map { user =>
      Ok(toJson(user.basicUser).as[JsObject] ++
         toJson(user.info).as[JsObject] ++
         Json.obj("experiments" -> request.experiments.map(_.value)))
    }
  }

}
