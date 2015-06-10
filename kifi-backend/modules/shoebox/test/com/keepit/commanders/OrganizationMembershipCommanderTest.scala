package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationMembershipCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  "organization membership commander" should {
    "get memberships by organization id" in {
      withDb() { implicit injector =>
        val orgId = Id[Organization](1)
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        db.readWrite { implicit session =>
          for { i <- 1 to 20 } yield {
            val userId = UserFactory.user().withId(Id[User](i)).saved.id.get
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = userId, access = OrganizationAccess.READ_WRITE))
          }
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]
        orgMemberCommander.getMembersAndInvitees(Id[Organization](0), Count(10), Offset(0), true).length === 0
        orgMemberCommander.getMembersAndInvitees(orgId, Count(50), Offset(0), true).length === 20
      }
    }
    "and page results" in {
      withDb() { implicit injector =>
        val orgId = Id[Organization](1)
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        db.readWrite { implicit session =>
          for { i <- 1 to 20 } yield {
            val userId = UserFactory.user().withId(Id[User](i)).saved.id.get
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = userId, access = OrganizationAccess.READ_WRITE))
          }
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]
        // limit by count
        val membersLimitedByCount = orgMemberCommander.getMembersAndInvitees(orgId, Count(10), Offset(0), true)
        membersLimitedByCount.length === 10

        // limit with offset
        val membersLimitedByOffset = orgMemberCommander.getMembersAndInvitees(orgId, Count(10), Offset(5), true)
        membersLimitedByOffset.length === 10
      }
    }
  }
}
