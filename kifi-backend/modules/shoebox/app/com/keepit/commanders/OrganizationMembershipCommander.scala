package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._

class OrganizationMembershipCommander @Inject() (db: Database, organizationMembershipRepo: OrganizationMembershipRepo) {
  // Offset and Count to prevent accidental reversal of arguments with same type.
  def getByOrgId(orgId: Id[Organization], count: Count, offset: Offset): Seq[OrganizationMembership] = {
    db.readWrite { implicit session =>
      organizationMembershipRepo.getbyOrgId(orgId, count, offset)
    }
  }

  //  POST    /m/1/teams/:id/members/invite               // takes a list of userId / access tuple's => { members: [ {userId: USER_ID, access: ACCESS}, ...] }
  //  POST    /m/1/teams/:id/members/modify               // same as above
  //  POST    /m/1/teams/:id/members/remove               // takes a list of userIds => { members: [ USER_ID1, USER_ID2] }
}
