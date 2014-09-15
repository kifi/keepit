package com.keepit.controllers.ext

import com.keepit.commanders.{ EmailContactResult, TypeaheadCommander, UserCommander, UserContactResult }
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import com.google.inject.Inject

class ExtUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  typeAheadCommander: TypeaheadCommander,
  userCommander: UserCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getFriends() = JsonAction.authenticated { request =>
    Ok(Json.toJson(userCommander.getFriends(request.user, request.experiments)))
  }

  def searchForContacts(query: Option[String], limit: Option[Int]) = JsonAction.authenticatedAsync { request =>
    typeAheadCommander.searchForContacts(request.userId, query.getOrElse(""), limit) map { res =>
      val res1 = res.map { r =>
        r match {
          case u: UserContactResult => Json.toJson(u)
          case e: EmailContactResult => Json.toJson(e)
        }
      }
      Ok(Json.toJson(res1))
    }
  }
}
