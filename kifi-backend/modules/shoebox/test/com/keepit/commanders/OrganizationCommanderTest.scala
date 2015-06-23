package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  "organization commander" should {
    "create an organization" in {
      withDb() { implicit injector =>
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val response = orgCommander.createOrganization(OrganizationCreateRequest(userId = Id[User](1), "Kifi"))
        response must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = response.right.get.newOrg

        orgCommander.get(org.id.get) === org
        val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
        memberships.length === 1
        memberships.head.userId === Id[User](1)
        memberships.head.role === OrganizationRole.OWNER
      }
    }

    "delete an organization" in {
      withDb() { implicit injector =>
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createResponse = orgCommander.createOrganization(OrganizationCreateRequest(userId = Id[User](1), "Kifi"))
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.MEMBER))
        }

        val deleteResponse = orgCommander.deleteOrganization(OrganizationDeleteRequest(orgId = org.id.get, requesterId = Id[User](1)))

        val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
        memberships.length === 0

        orgCommander.get(org.id.get) === deleteResponse.right.get.deactivatedOrg
      }
    }
  }
}
