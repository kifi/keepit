package com.keepit.controllers.ext

import com.keepit.commanders._
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.kifi.macros.json

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsObject, JsValue, JsNull, Json }

import com.google.inject.Inject

import scala.concurrent.Future

class ExtUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeAheadCommander: TypeaheadCommander,
  libPathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgMemberRepo: OrganizationMembershipRepo,
  basicOrganizationGen: BasicOrganizationGen,
  db: Database,
  implicit val config: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>

    val typeaheadF = typeAheadCommander.searchForContactResults(request.userId, query.getOrElse(""), limit, includeSelf = true)

    val orgsToInclude = {
      val basicOrgs = db.readOnlyReplica { implicit s =>
        val orgsUserIsIn = orgMemberRepo.getAllByUserId(request.userId).map(_.organizationId)
          .filter { orgId =>
            permissionCommander.getOrganizationPermissions(orgId, Some(request.userId)).contains(OrganizationPermission.GROUP_MESSAGING)
          }
        basicOrganizationGen.getBasicOrganizations(orgsUserIsIn.toSet).values
      }
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
          "handle" -> org.handle.value
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

  @json case class RecipientSuggestion(query: Option[String], results: Seq[JsObject /* TypeaheadResult */ ], mayHaveMore: Boolean, limit: Option[Int], drop: Option[Int])
  def suggestRecipient(query: Option[String], limit: Option[Int], drop: Option[Int], requested: Option[String]) = UserAction.async { request =>
    val requestedSet = requested.map(_.split(",").map(_.trim).flatMap(TypeaheadRequest.applyOpt).toSet).filter(_.nonEmpty).getOrElse(TypeaheadRequest.all)
    typeAheadCommander.searchAndSuggestKeepRecipients(request.userId, query.getOrElse(""), limit, drop, requestedSet).map { suggestions =>
      val body = suggestions.take(limit.getOrElse(20)).collect {
        case u: UserContactResult => Json.toJson(u).as[JsObject] ++ Json.obj("kind" -> "user")
        case e: EmailContactResult => Json.toJson(e).as[JsObject] ++ Json.obj("kind" -> "email")
        case l: LibraryResult => Json.toJson(l).as[JsObject] ++ Json.obj("kind" -> "library")
      }
      Ok(Json.toJson(RecipientSuggestion(query.map(_.trim).filter(_.nonEmpty), body, suggestions.nonEmpty, limit, drop)))
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
