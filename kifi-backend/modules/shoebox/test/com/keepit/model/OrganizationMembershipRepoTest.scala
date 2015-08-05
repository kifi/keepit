package com.keepit.model

import com.keepit.model.OrganizationFactory._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.collection.immutable.IndexedSeq
import scala.util.Random

class OrganizationMembershipRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Member Repo" should {
    "save members and get them by id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val org = db.readWrite { implicit s =>
          organization().withOwner(user().saved).saved
        }

        db.readOnlyMaster { implicit s =>
          val membership = orgMemberRepo.getAllByUserId(org.ownerId).head
          membership.organizationId == org.id.get
          membership.userId == org.ownerId
        }
      }
    }

    "get by organization id and user id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val (org, inactive, owner, member) = db.readWrite { implicit s =>
          val owner = user().saved
          val member = user().saved
          val org = organization().withOwner(owner).saved
          val inactive = orgMemberRepo.save(org.newMembership(userId = member.id.get, role = OrganizationRole.ADMIN).withState(OrganizationMembershipStates.INACTIVE))
          (org, inactive, owner, member)
        }

        val orgId = org.id.get
        val notUsedOrgId = Id[Organization](10)

        db.readOnlyMaster { implicit s =>
          orgMemberRepo.getByOrgIdAndUserId(notUsedOrgId, owner.id.get) must beNone
          orgMemberRepo.getByOrgIdAndUserId(orgId, owner.id.get).get.role === OrganizationRole.ADMIN
          orgMemberRepo.getByOrgIdAndUserId(orgId, member.id.get, excludeState = Some(OrganizationMembershipStates.ACTIVE)) must beSome(inactive)
        }
      }
    }

    "get by user id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val (orgs, owner) = db.readWrite { implicit session =>
          val owner = user().saved
          val member = user().saved
          val orgs = organizations(10).map(_.withOwner(owner)).saved
          orgMemberRepo.save(orgs(0).newMembership(role = OrganizationRole.MEMBER, userId = member.id.get))
          (orgs, owner)
        }

        val memberships = db.readOnlyMaster { implicit session => orgMemberRepo.getAllByUserId(owner.id.get) }
        memberships.length === orgs.length
        memberships.map(_.organizationId).diff(orgs.map(_.id.get)) === List.empty[Id[Organization]]
      }
    }

    "get by org id and userIds" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val (org, otherOrg, members) = db.readWrite { implicit session =>
          val members = users(5).saved
          val org = organization().withOwner(user().saved).withMembers(members).saved
          val otherOrg = organization().withOwner(user().saved).withMembers(members).saved
          (org, otherOrg, members)
        }

        val getUserIds = members.drop(2).map(_.id.get).toSet
        val memberships = db.readOnlyMaster { implicit session => orgMemberRepo.getByOrgIdAndUserIds(org.id.get, getUserIds) }
        memberships.length === getUserIds.size
        memberships.map(_.userId).diff(getUserIds.toSeq) === List.empty
      }
    }

    "get list of members sorted by 1) owner, members, pending invites, 2) first name, last name alphabetically" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val (org, owner, members) = db.readWrite { implicit session =>
          val owner = user.withName("Zyxwv", "Utsr").saved
          val members = Random.shuffle(Seq(user.withName("Aaron", "Aaronson").saved, user.withName("Barry", "Barnes").saved, user.withName("Carl", "Carson").saved, user.withName("Carl", "Junior").saved))
          val org = organization().withOwner(owner).withMembers(members).saved
          (org, owner, members)
        }
        val sortedMembers = db.readOnlyMaster { implicit session => orgMemberRepo.getSortedMembershipsByOrgId(org.id.get) }

        sortedMembers.exists(_.userId == owner.id.get) === true

        db.readOnlyMaster { implicit s => orgMemberRepo.getByOrgIdAndUserId(org.id.get, owner.id.get) }.isDefined === true

        val userById = members.+:(owner) groupBy (_.id.get)

        def isSorted(sortedMembers: Seq[OrganizationMembership], userById: Map[Id[User], Seq[User]]): Boolean = {

          def isSortedHelper(members: Seq[OrganizationMembership]): Boolean = {
            members match {
              case Nil => true
              case x :: Nil => true
              case x :: xs => (userById(x.userId).head.firstName < userById(xs.head.userId).head.firstName || userById(x.userId).head.lastName < userById(xs.head.userId).head.lastName) && isSortedHelper(members.tail)
            }
          }

          sortedMembers.head.userId == owner.id.get && isSortedHelper(sortedMembers.tail)
        }

        isSorted(sortedMembers, userById) === true
        isSorted(sortedMembers.reverse, userById) === false

      }
    }

    "deactivate" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val membership = db.readWrite { implicit session =>
          val org = organization.withOwner(user().saved).saved
          orgMemberRepo.save(org.newMembership(role = OrganizationRole.MEMBER, userId = user().saved.id.get))
        }

        val deactivate = db.readWrite { implicit session => orgMemberRepo.deactivate(membership) }
        membership.state === OrganizationMembershipStates.ACTIVE
        deactivate.state === OrganizationMembershipStates.INACTIVE
      }
    }
  }
}
