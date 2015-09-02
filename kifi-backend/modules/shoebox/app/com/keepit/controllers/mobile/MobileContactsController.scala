package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders.{ EmailContactResult, UserContactResult, TypeaheadCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsString }
import com.keepit.abook.model.RichContact

class MobileContactsController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeaheadCommander: TypeaheadCommander)
    extends UserActions with ShoeboxServiceController {

  def searchForNonUserContacts(q: String, n: Int) = UserAction.async { request =>
    new SafeFuture({
      typeaheadCommander.queryNonUserContacts(request.userId, q, n)
    }) map { contacts =>
      Ok(JsArray(contacts.map(serializeContact)))
    }
  }

  def searchForAllContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    // XXXX

    typeaheadCommander.searchForContacts(request.userId, query.getOrElse(""), limit) map { contactSearchResult =>
      val response = contactSearchResult.collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
      }
      Ok(Json.toJson(response))
    }
  }

  def serializeContact(contact: RichContact): JsArray = {
    JsArray(
      Seq(JsString(contact.email.address)) ++
        contact.name.map(JsString))
  }

}
