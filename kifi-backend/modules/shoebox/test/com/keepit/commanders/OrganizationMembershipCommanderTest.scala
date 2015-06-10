package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationMembershipCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  "organization membership commander" should {
    "get memberships by organization id" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        db.readWrite { implicit session =>
          for { i <- 1 to 20 } yield {
            orgMemberRepo.save(OrganizationMembership(organizationId = Id[Organization](1), userId = Id[User](i), access = OrganizationAccess.READ_WRITE))
          }
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]
        orgMemberCommander.getByOrgId(Id[Organization](0), Count(10), Offset(0)).length === 0
        val members = orgMemberCommander.getByOrgId(Id(1), Count(50), Offset(0))
        members.length === 20
      }
    }
    "and page results" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val orgId = Id[Organization](1)
        db.readWrite { implicit session =>
          for { i <- 1 to 20 } yield {
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](i), access = OrganizationAccess.READ_WRITE))
          }
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]
        // limit by count
        val membersLimitedByCount = orgMemberCommander.getByOrgId(orgId, Count(10), Offset(0))
        membersLimitedByCount.length === 10
        for { i <- 1 to 10 } yield {
          membersLimitedByCount(i - 1).userId === Id[User](i)
        }

        // limit with offset
        val membersLimitedByOffset = orgMemberCommander.getByOrgId(orgId, Count(10), Offset(5))
        for { i <- 1 to 10 } yield {
          membersLimitedByOffset(i - 1).userId === Id[User](i + 5)
        }
        // can't end with a for comprehension
        membersLimitedByOffset.length === 10
      }
    }
  }
}
