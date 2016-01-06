package com.keepit.controllers.ext

import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.model._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNull, Json }

import com.google.inject.Inject

class ExtUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeAheadCommander: TypeaheadCommander,
  libPathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgMemberRepo: OrganizationMembershipRepo,
  orgInfoCommander: OrganizationInfoCommander,
  db: Database,
  implicit val config: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>

    val typeaheadF = typeAheadCommander.searchForContacts(request.userId, query.getOrElse(""), limit)

    val orgsToInclude = {
      val orgsUserIsIn = db.readOnlyReplica { implicit s =>
        orgMemberRepo.getAllByUserId(request.userId).map(_.organizationId)
          .filter { orgId =>
            permissionCommander.getOrganizationPermissions(orgId, Some(request.userId)).contains(OrganizationPermission.GROUP_MESSAGING)
          }
      }
      val basicOrgs = orgInfoCommander.getBasicOrganizations(orgsUserIsIn.toSet).values
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

  def getGuideInfo = UserAction { request =>
    Ok(Json.obj("keep" -> Json.obj(
      "url" -> "http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die",
      "image" -> Json.obj("url" -> "//d1dwdv9wd966qu.cloudfront.net/img/guide/steve_960x892.d25b7d8.jpg", "width" -> 480, "height" -> 446),
      "noun" -> "video",
      "query" -> "steve+jobs",
      "title" -> "Steve Jobs: How to live before you die | Talk Video | TED.com",
      "matches" -> Json.obj("title" -> Json.toJson(Seq(Seq(0, 5), Seq(6, 4))), "url" -> Json.toJson(Seq(Seq(25, 5), Seq(31, 4)))),
      "track" -> "steveJobsSpeech"
    )))
  }
}
