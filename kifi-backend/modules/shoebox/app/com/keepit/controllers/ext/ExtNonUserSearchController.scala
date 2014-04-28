package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.model.EContact
import com.keepit.typeahead.TypeaheadHit
import com.keepit.typeahead.abook.EContactTypeahead

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue}

class ExtNonUserSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  econtactTypeahead: EContactTypeahead)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def findPeopleToInvite(q: String, n: Int) = JsonAction.authenticated { request =>
    Ok(JsArray())
  }

  def findPeopleToMessage(q: String, n: Int) = JsonAction.authenticatedAsync { request =>
    new SafeFuture({
      econtactTypeahead.asyncSearch(request.userId, q)(TypeaheadHit.defaultOrdering[EContact])
    }) map { contactsOpt =>
      contactsOpt map { contacts =>
        Ok(JsArray(contacts.filterNot(_.contactUserId.isDefined).take(n).map(serializeContact)))
      } getOrElse { // TODO: airbrake?
        Ok(JsArray())
      }
    }
  }

  def serializeContact(contact: EContact): JsObject = {
    JsObject(Seq[(String, JsValue)](
      "email" -> JsString(contact.email)) ++
      contact.name.map {name => "name" -> JsString(name)})
  }

}
