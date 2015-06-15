package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.collection.immutable.IndexedSeq

class OrganizationMembershipRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Member Repo" should {
    "save members and get them by id" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val org = db.readWrite { implicit s =>
          orgMemberRepo.save(OrganizationMembership(organizationId = Id[Organization](1), userId = Id[User](1), role = OrganizationRole.OWNER))
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
          val active = orgMemberRepo.save(OrganizationMembership(organizationId = organizationId, userId = userId, role = OrganizationRole.OWNER))
          val inactive = orgMemberRepo.save(OrganizationMembership(organizationId = organizationId, userId = userId, role = OrganizationRole.OWNER).withState(OrganizationMembershipStates.INACTIVE))
          (active, inactive)
        }

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.getByOrgIdAndUserId(notUsedOrgId, userId) must beNone
          orgMemberRepo.getByOrgIdAndUserId(organizationId, userId) must equalTo(Some(activeMember))
          orgMemberRepo.getByOrgIdAndUserId(organizationId, userId, excludeState = Some(OrganizationMembershipStates.ACTIVE)) must beSome(inactiveMember)
        }
      }
    }

    "get by user id" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val userId = Id[User](1)
        val orgIds: IndexedSeq[Id[Organization]] = 5 to 15 map (Id[Organization](_))
        db.readWrite { implicit session =>
          orgMemberRepo.save(OrganizationMembership(organizationId = Id(1), role = OrganizationRole.MEMBER, userId = Id[User](2)))
          for { orgId <- orgIds } {
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, role = OrganizationRole.MEMBER, userId = userId))
          }
        }

        val memberships = db.readOnlyMaster { implicit session => orgMemberRepo.getByUserId(userId) }
        memberships.length === orgIds.length
        memberships.map(_.organizationId).diff(orgIds) === List.empty[Id[Organization]]
      }
    }

    "get by org id and userIds" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val orgId = Id[Organization](1)
        val otherOrgId = Id[Organization](5)
        val userIds: IndexedSeq[Id[User]] = 5 to 10 map (Id[User](_))
        db.readWrite { implicit session =>
          for (userId <- userIds) {
            orgMemberRepo.save(OrganizationMembership(organizationId = otherOrgId, role = OrganizationRole.MEMBER, userId = userId))
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, role = OrganizationRole.MEMBER, userId = userId))
          }
        }

        val getUserIds = Set(Id[User](5), Id[User](6), Id[User](7))
        val memberships = db.readOnlyMaster { implicit session => orgMemberRepo.getByOrgIdAndUserIds(orgId, getUserIds) }
        memberships.length === getUserIds.size
        memberships.map(_.userId).diff(getUserIds.toSeq) === List.empty[Id[Organization]]
      }
    }

    "deactivate" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val orgId = Id[Organization](1)
        val userId = Id[User](1)
        val membership = db.readWrite { implicit session =>
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, role = OrganizationRole.MEMBER, userId = userId))
        }

        val deactivate = db.readWrite { implicit session => orgMemberRepo.deactivate(membership.id.get) }
        membership.state === OrganizationMembershipStates.ACTIVE
        deactivate.state === OrganizationMembershipStates.INACTIVE
      }
    }
  }
}
