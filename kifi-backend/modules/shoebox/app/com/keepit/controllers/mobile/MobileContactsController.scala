package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders.{ OrganizationCommander, EmailContactResult, UserContactResult, TypeaheadCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationMembershipRepo, UserExperimentType }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsString }
import com.keepit.abook.model.RichContact

class MobileContactsController @Inject() (
  val userActionsHelper: UserActionsHelper,
  orgCommander: OrganizationCommander,
  orgMemberRepo: OrganizationMembershipRepo,
  db: Database,
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
    val typeaheadF = typeaheadCommander.searchForContacts(request.userId, query.getOrElse(""), limit)

    val orgsToInclude = {
      val orgsUserIsIn = db.readOnlyReplica(implicit s => orgMemberRepo.getAllByUserId(request.userId).map(_.organizationId))
      val basicOrgs = orgCommander.getBasicOrganizations(orgsUserIsIn.toSet).values
      val orgsToShow = query.getOrElse("") match {
        case "" => basicOrgs
        case orgQ =>
          def sanitize(s: String) = s.trim.toLowerCase.split("\\P{L}+").toSet
          val query = sanitize(orgQ)
          basicOrgs.filter { o =>
            val lowerOrg = o.name.toLowerCase
            query.forall(qterm => lowerOrg.contains(qterm))
          }
      }
      orgsToShow.map { org =>
        // This is a superset of a UserContact. I can't use that type because it forces ExternalId[User]
        Json.obj(
          "name" -> (org.name + " Members"),
          "id" -> org.orgId,
          "pictureName" -> ("../../../../" + org.avatarPath.path: String), // one weird trick
          "kind" -> "org",
          "avatarPath" -> (org.avatarPath.path: String),
          "handle" -> org.handle
        )
      }.toList
    }

    typeaheadF.map { res =>
      val orgCount = orgsToInclude.length
      val contactsToShow = limit.getOrElse(res.length + orgCount) - orgCount

      val res1 = orgsToInclude ++ res.take(contactsToShow).collect {
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
