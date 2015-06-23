package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.collection.immutable.IndexedSeq

class OrganizationMembershipRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Member Repo" should {
    "save members and get them by id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val (org, membership) = db.readWrite { implicit s =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          val membership = orgMemberRepo.save(org.newMembership(userId = Id[User](1), role = OrganizationRole.OWNER))
          (org, membership)
        }

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.get(membership.id.get) === membership
        }
      }
    }

    "get by organization id and user id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val userId = Id[User](1)

        val (org, activeMember, inactiveMember) = db.readWrite { implicit s =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          val active = orgMemberRepo.save(org.newMembership(userId = userId, role = OrganizationRole.OWNER))
          val inactive = orgMemberRepo.save(org.newMembership(userId = userId, role = OrganizationRole.OWNER).withState(OrganizationMembershipStates.INACTIVE))
          (org, active, inactive)
        }

        val orgId = org.id.get
        val notUsedOrgId = Id[Organization](10)

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.getByOrgIdAndUserId(notUsedOrgId, userId) must beNone
          orgMemberRepo.getByOrgIdAndUserId(orgId, userId) must equalTo(Some(activeMember))
          orgMemberRepo.getByOrgIdAndUserId(orgId, userId, excludeState = Some(OrganizationMembershipStates.ACTIVE)) must beSome(inactiveMember)
        }
      }
    }

    "get by user id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val orgs = db.readWrite { implicit session =>
          val orgs = for (i <- 1 to 10) yield orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          for (org <- orgs) orgMemberRepo.save(org.newMembership(role = OrganizationRole.OWNER, userId = Id[User](1)))

          orgMemberRepo.save(orgs(0).newMembership(role = OrganizationRole.MEMBER, userId = Id[User](2)))
          orgs
        }

        val memberships = db.readOnlyMaster { implicit session => orgMemberRepo.getByUserId(Id[User](1)) }
        memberships.length === orgs.length
        memberships.map(_.organizationId).diff(orgs.map(_.id.get)) === List.empty[Id[Organization]]
      }
    }

    "get by org id and userIds" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val userIds: IndexedSeq[Id[User]] = 5 to 10 map (Id[User](_))
        val (org, otherOrg) = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          val otherOrg = orgRepo.save(Organization(ownerId = Id[User](2), name = "Superman Corp.", handle = None))

          for (userId <- userIds) {
            orgMemberRepo.save(otherOrg.newMembership(role = OrganizationRole.MEMBER, userId = userId))
            orgMemberRepo.save(org.newMembership(role = OrganizationRole.MEMBER, userId = userId))
          }

          (org, otherOrg)
        }

        val getUserIds = Set(Id[User](5), Id[User](6), Id[User](7))
        val memberships = db.readOnlyMaster { implicit session => orgMemberRepo.getByOrgIdAndUserIds(org.id.get, getUserIds) }
        memberships.length === getUserIds.size
        memberships.map(_.userId).diff(getUserIds.toSeq) === List.empty[Id[Organization]]
      }
    }

    "deactivate" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val userId = Id[User](1)
        val membership = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          orgMemberRepo.save(org.newMembership(role = OrganizationRole.MEMBER, userId = userId))
        }

        val deactivate = db.readWrite { implicit session => orgMemberRepo.deactivate(membership.id.get) }
        membership.state === OrganizationMembershipStates.ACTIVE
        deactivate.state === OrganizationMembershipStates.INACTIVE
      }
    }
  }
}
