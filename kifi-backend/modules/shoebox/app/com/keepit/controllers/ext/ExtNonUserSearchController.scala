package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders.TypeaheadCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, UserActions, UserActionsHelper }

import com.keepit.common.mail.EmailAddress

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsObject, JsString, JsValue }
import com.keepit.abook.model.RichContact

import scala.concurrent.Future

class ExtNonUserSearchController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeaheadCommander: TypeaheadCommander)
    extends UserActions with ShoeboxServiceController {

  def findPeopleToMessage(q: String, n: Int) = UserAction.async { request =>
    new SafeFuture({
      typeaheadCommander.queryNonUserContacts(request.userId, q, n)
    }) map { contacts =>
      Ok(JsArray(contacts.map(serializeContact)))
    }
  }

  def serializeContact(contact: RichContact): JsObject = {
    JsObject(Seq[(String, JsValue)](
      "email" -> Json.toJson(contact.email)) ++
      contact.name.map { name => "name" -> JsString(name) })
  }

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
