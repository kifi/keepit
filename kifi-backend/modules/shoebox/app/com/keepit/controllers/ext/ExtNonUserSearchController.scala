package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders.TypeaheadCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }

import com.keepit.common.mail.EmailAddress

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

class ExtNonUserSearchController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeaheadCommander: TypeaheadCommander)
    extends UserActions with ShoeboxServiceController {

  def hideEmailFromUser() = UserAction.async(parse.tolerantJson) { request =>
    (request.body \ "email").asOpt[EmailAddress] map { email =>
      new SafeFuture[Boolean]({
        typeaheadCommander.hideEmailFromUser(request.userId, email)
      }) map { result =>
        Ok(Json.toJson(result))
      }
    } getOrElse Future.successful(BadRequest(Json.obj("email" -> "Email address missing or invalid")))
  }
}
