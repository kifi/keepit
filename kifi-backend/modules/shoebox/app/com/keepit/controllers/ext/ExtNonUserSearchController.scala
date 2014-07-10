package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders.TypeaheadCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }

import com.keepit.common.mail.EmailAddress
import com.keepit.model.EContact

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsObject, JsString, JsValue }
import com.keepit.abook.RichContact

class ExtNonUserSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  typeaheadCommander: TypeaheadCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def findPeopleToInvite(q: String, n: Int) = JsonAction.authenticated { request =>
    Ok(JsArray())
  }

  def findPeopleToMessage(q: String, n: Int) = JsonAction.authenticatedAsync { request =>
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

  def hideEmailFromUser(email: String) = JsonAction.authenticatedAsync { request =>
    new SafeFuture[Boolean]({
      typeaheadCommander.hideEmailFromUser(request.userId, EmailAddress(email))
    }) map { result =>
      Ok(Json.toJson(result))
    }
  }
}
