package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json.{ Json, JsSuccess, JsError }
import play.api.mvc.Result

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

@Singleton
class MobileOrganizationController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  // TODO: add to commander:
  // getOrganizationView
  // getOrganizationCard

  def createOrganization = UserAction.async(parse.tolerantJson) { request =>
    request.body.validate[OrganizationModifications] match {
      case _: JsError =>
        Future.successful(BadRequest)
      case JsSuccess(initialValues, _) =>
        val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
        orgCommander.createOrganization(createRequest) match {
          case Left(failure) =>
            Future.successful(failure.asErrorResponse)
          case Right(response) =>
            val orgView = orgCommander.getFullOrganizationInfo(response.newOrg.id.get)
            Future.successful(Ok(Json.toJson(orgView)))
        }
    }
  }

  def getOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    Ok(Json.toJson(orgCommander.getFullOrganizationInfo(request.orgId)))
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = UserAction { request =>
    // TODO: provide a Json thing for a bunch of OrganizationCards
    Ok
  }
}
