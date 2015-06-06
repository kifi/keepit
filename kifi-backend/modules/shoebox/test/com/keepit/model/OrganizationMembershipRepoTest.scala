package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OrganizationMembershipRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Member Repo" should {
    "save members and get them by id" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val org = db.readWrite { implicit s =>
          orgMemberRepo.save(OrganizationMembership(organizationId = Id[Organization](1), userId = Id[User](1), access = OrganizationAccess.OWNER))
        }

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.get(org.id.get) === org
        }
      }
    }

    "get by organization id and user id" in {
      withDb() { implicit injector =>
        val notUsedOrgId = Id[Organization](10)
        val organizationId = Id[Organization](1)
        val userId = Id[User](1)

        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val (activeMember, inactiveMember) = db.readWrite { implicit s =>
          val active = orgMemberRepo.save(OrganizationMembership(organizationId = organizationId, userId = userId, access = OrganizationAccess.OWNER))
          val inactive = orgMemberRepo.save(OrganizationMembership(organizationId = organizationId, userId = userId, access = OrganizationAccess.OWNER, state = OrganizationMembershipStates.INACTIVE))
          (active, inactive)
        }

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.getByOrgIdAndUserId(notUsedOrgId, userId) must beNone
          orgMemberRepo.getByOrgIdAndUserId(organizationId, userId) must equalTo(Some(activeMember))
          orgMemberRepo.getByOrgIdAndUserId(organizationId, userId, excludeState = Some(OrganizationMembershipStates.ACTIVE)) must beSome(inactiveMember)
        }
      }
    }
  }
}
