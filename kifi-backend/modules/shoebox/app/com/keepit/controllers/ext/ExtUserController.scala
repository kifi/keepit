package com.keepit.controllers.ext

import com.keepit.commanders.{ UserConnectionsCommander, EmailContactResult, TypeaheadCommander, UserCommander, UserContactResult }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import com.google.inject.Inject

class ExtUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeAheadCommander: TypeaheadCommander,
  userCommander: UserConnectionsCommander)
    extends UserActions with ShoeboxServiceController {

  def getFriends() = UserAction { request =>
    Ok(Json.toJson(userCommander.getFriends(request.user, request.experiments)))
  }

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
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
