package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationPermission, OrganizationMembershipRepo, UserExperimentType }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsString }
import com.keepit.abook.model.RichContact

class MobileContactsController @Inject() (
  val userActionsHelper: UserActionsHelper,
  basicOrganizationGen: BasicOrganizationGen,
  orgMemberRepo: OrganizationMembershipRepo,
  db: Database,
  typeaheadCommander: TypeaheadCommander,
  permissionCommander: PermissionCommander)
    extends UserActions with ShoeboxServiceController {

  def searchForNonUserContacts(q: String, n: Int) = UserAction.async { request =>
    new SafeFuture({
      typeaheadCommander.queryNonUserContacts(request.userId, q, n)
    }) map { contacts =>
      Ok(JsArray(contacts.map(serializeContact)))
    }
  }

  def searchForAllContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    val typeaheadF = typeaheadCommander.searchForContactResults(request.userId, query.getOrElse(""), limit, includeSelf = false)

    typeaheadF.map { res =>
      val contactsToShow = limit.getOrElse(res.length)
      val res1 = res.take(contactsToShow).collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
      }
      Ok(Json.toJson(res1))
    }
  }

  def serializeContact(contact: RichContact): JsArray = {
    JsArray(
      Seq(JsString(contact.email.address)) ++
        contact.name.map(JsString))
  }

}
