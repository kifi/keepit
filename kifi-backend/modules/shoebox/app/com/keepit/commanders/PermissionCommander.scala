package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[PermissionCommanderImpl])
trait PermissionCommander {
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]
}

@Singleton
class PermissionCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgInviteRepo: OrganizationInviteRepo,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    userExperimentRepo: UserExperimentRepo,
    orgExperimentRepo: OrganizationExperimentRepo,
    implicit val executionContext: ExecutionContext) extends PermissionCommander with Logging {

  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
    val org = orgRepo.get(orgId)
    userIdOpt match {
      case None => org.getNonmemberPermissions
      case Some(userId) =>
        orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).map(_.permissions) getOrElse {
          val invites = orgInviteRepo.getByOrgIdAndUserId(orgId, userId)
          if (invites.isEmpty) org.getNonmemberPermissions
          else org.getNonmemberPermissions + OrganizationPermission.VIEW_ORGANIZATION
        }
    }
  }
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = ???
}
