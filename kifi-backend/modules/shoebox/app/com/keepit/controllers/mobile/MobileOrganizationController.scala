package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions

import scala.concurrent.{ ExecutionContext, Future }

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
    // TODO: do this
    Future.successful(Ok)
  }

  def getOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    // TODO: provide a Json thing for an OrganizationView
    Ok
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = UserAction { request =>
    // TODO: provide a Json thing for a bunch of OrganizationCards
    Ok
  }
}
