package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders.TypeaheadCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsArray, JsString}
import com.keepit.abook.RichContact

class MobileContactsController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  typeaheadCommander: TypeaheadCommander)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def search(q: String, n: Int) = JsonAction.authenticatedAsync { request =>
    new SafeFuture({
      typeaheadCommander.queryNonUserContacts(request.userId, q, n)
    }) map { contacts =>
      Ok(JsArray(contacts.map(serializeContact)))
    }
  }

  def serializeContact(contact: RichContact): JsArray = {
    JsArray(
      Seq(JsString(contact.email.address)) ++
      contact.name.map(JsString))
  }

}
