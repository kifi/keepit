package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders.TypeaheadCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.model.EContact

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsArray, JsString}

import scala.Some

class MobileContactsController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  typeaheadCommander: TypeaheadCommander)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def search(q: String, n: Int) = JsonAction.authenticatedAsync { request =>
    new SafeFuture({
      typeaheadCommander.queryContacts(request.userId, Some(q), n, _.contactUserId.isEmpty)
    }) map { contacts =>
      Ok(JsArray(contacts.map(serializeContact)))
    }
  }

  def serializeContact(contact: EContact): JsArray = {
    JsArray(
      Seq(JsString(contact.email.address)) ++
      contact.name.map(JsString))
  }

}
