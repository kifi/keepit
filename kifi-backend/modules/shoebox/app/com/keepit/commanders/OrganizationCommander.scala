package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def get(orgId: Id[Organization]): Organization
  def canViewOrganization(userIdOpt: Option[Id[User]], orgId: Id[Organization], authToken: Option[String]): Boolean
}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo) extends OrganizationCommander with Logging {

  def get(orgId: Id[Organization]): Organization = db.readOnlyReplica { implicit session => orgRepo.get(orgId) }

  // Right now, Organization's are 100% public
  def canViewOrganization(userIdOpt: Option[Id[User]], orgId: Id[Organization], authToken: Option[String]): Boolean = true
}
