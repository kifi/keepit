package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def get(orgId: Id[Organization]): Organization
}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo) extends OrganizationCommander with Logging {

  def get(orgId: Id[Organization]): Organization = db.readOnlyReplica { implicit session => orgRepo.get(orgId) }
}
