package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders.TypeaheadCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.model.EContact

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsArray, JsObject, JsString}

import scala.Some

class ExtNonUserSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  typeaheadCommander: TypeaheadCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def findPeopleToInvite(q: String, n: Int) = JsonAction.authenticated { request =>
    Ok(JsArray())
  }

  def findPeopleToMessage(q: String, n: Int) = JsonAction.authenticatedAsync { request =>
    new SafeFuture({
      typeaheadCommander.queryContacts(request.userId, Some(q), n, _.contactUserId.isEmpty)
    }) map { contacts =>
      Ok(JsArray(contacts.map(serializeContact)))
    }
  }

  def serializeContact(contact: EContact): JsObject = {
    JsObject(Seq[(String, JsValue)](
      "email" -> Json.toJson(contact.email)) ++
      contact.name.map {name => "name" -> JsString(name)})
  }

}
