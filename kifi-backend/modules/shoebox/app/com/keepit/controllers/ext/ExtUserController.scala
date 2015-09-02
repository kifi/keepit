package com.keepit.controllers.ext

import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationMembershipRepo, OrganizationRepo, UserExperimentType, Library }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import com.google.inject.Inject

class ExtUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeAheadCommander: TypeaheadCommander,
  libPathCommander: PathCommander,
  userPersonaCommander: UserPersonaCommander,
  orgMemberRepo: OrganizationMembershipRepo,
  orgRepo: OrganizationRepo,
  orgCommander: OrganizationCommander,
  db: Database,
  implicit val config: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>

    val typeaheadF = typeAheadCommander.searchForContacts(request.userId, query.getOrElse(""), limit)

    val orgsToInclude = if (request.experiments.contains(UserExperimentType.ADMIN)) {
      val orgsUserIsIn = db.readOnlyReplica(implicit s => orgMemberRepo.getAllByUserId(request.userId).map(_.organizationId))
      val basicOrgs = orgCommander.getBasicOrganizations(orgsUserIsIn.toSet).values
      val orgsToShow = query.getOrElse("") match {
        case "" => basicOrgs
        case orgQ =>
          def sanitize(s: String) = s.trim.toLowerCase.split("\\P{L}+").toSet
          val q = sanitize(orgQ)
          basicOrgs.filter(o => q.subsetOf(sanitize(o.name)))
      }
      orgsToShow.map { org =>
        // This is a superset of a UserContact. I can't use that type because it forces ExternalId[User]
        Json.obj(
          "name" -> (org.name + " Members"),
          "id" -> org.orgId,
          "pictureName" -> ("../../../../" + org.avatarPath.map(_.path).getOrElse("NONE"): String), // one weird trick
          "kind" -> "org",
          "avatarPath" -> (org.avatarPath.map(_.path).getOrElse("NONE"): String),
          "handle" -> org.handle
        )
      }.toList
    } else List.empty

    typeaheadF.map { res =>
      val res1 = res.collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
      } ++ orgsToInclude
      Ok(Json.toJson(res1))
    }
  }

  def getGuideInfo() = UserAction { request =>
    val (personaKeep, libOpt) = userPersonaCommander.getPersonaKeepAndLibrary(request.userId)
    Ok(Json.obj(
      "keep" -> personaKeep,
      "library" -> libOpt.map { lib =>
        Json.obj(
          "id" -> Library.publicId(lib.id.get),
          "name" -> lib.name,
          "path" -> libPathCommander.getPathForLibrary(lib),
          "color" -> lib.color)
      }
    ))
  }
}
