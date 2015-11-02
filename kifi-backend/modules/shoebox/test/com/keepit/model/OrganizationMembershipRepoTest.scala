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
        val org = db.readWrite { implicit s =>
          organization().withOwner(user().saved).saved
        }

        db.readOnlyMaster { implicit s =>
          val membership = orgMembershipRepo.getAllByUserId(org.ownerId).head
          membership.organizationId === org.id.get
          membership.userId === org.ownerId
        }
      }
    }

    "get by organization id and user id" in {
      withDb() { implicit injector =>
        val (org, inactive, owner, member) = db.readWrite { implicit s =>
          val owner = user().saved
          val member = user().saved
          val org = organization().withOwner(owner).saved
          val inactive = orgMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = member.id.get, role = OrganizationRole.ADMIN).withState(OrganizationMembershipStates.INACTIVE))
          (org, inactive, owner, member)
        }

        val orgId = org.id.get
        val notUsedOrgId = Id[Organization](10)

        db.readOnlyMaster { implicit s =>
          orgMembershipRepo.getByOrgIdAndUserId(notUsedOrgId, owner.id.get) must beNone
          orgMembershipRepo.getByOrgIdAndUserId(orgId, owner.id.get).get.role === OrganizationRole.ADMIN
          orgMembershipRepo.getByOrgIdAndUserId(orgId, member.id.get, excludeState = Some(OrganizationMembershipStates.ACTIVE)) must beSome(inactive)
        }
      }
    }

    "get by user id" in {
      withDb() { implicit injector =>
        val (orgs, owner) = db.readWrite { implicit session =>
          val owner = user().saved
          val orgs = organizations(10).map(_.withOwner(owner)).saved
          (orgs, owner)
        }

        val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByUserId(owner.id.get) }
        memberships.length === orgs.length
        memberships.map(_.organizationId).diff(orgs.map(_.id.get)) === List.empty[Id[Organization]]
      }
    }

    "get by org id and userIds" in {
      withDb() { implicit injector =>
        val (org, otherOrg, members) = db.readWrite { implicit session =>
          val members = users(5).saved
          val org = organization().withOwner(user().saved).withMembers(members).saved
          val otherOrg = organization().withOwner(user().saved).withMembers(members).saved
          (org, otherOrg, members)
        }

        val getUserIds = members.drop(2).map(_.id.get).toSet
        val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getByOrgIdAndUserIds(org.id.get, getUserIds) }
        memberships.length === getUserIds.size
        memberships.map(_.userId).diff(getUserIds.toSeq) === List.empty
      }
    }

    "get list of members sorted by 1) owner, members, pending invites, 2) first name, last name alphabetically" in {
      withDb() { implicit injector =>
        val (org, owner, members) = db.readWrite { implicit session =>
          val owner = user().withName("Zyxwv", "Utsr").saved
          val members = Random.shuffle(Seq(user().withName("Aaron", "Aaronson"), user().withName("Barry", "Barnes"), user().withName("Carl", "Carson"), user().withName("Carl", "Junior"))).map(_.saved)
          val org = organization().withOwner(owner).withMembers(members).saved
          (org, owner, members)
        }
        val allMembers = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }.toSeq
        val sortedMembers = db.readOnlyMaster { implicit session => orgMembershipRepo.getSortedMembershipsByOrgId(org.id.get, Offset(0), Limit(Int.MaxValue)) }

        db.readOnlyMaster { implicit s => orgMembershipRepo.getByOrgIdAndUserId(org.id.get, owner.id.get) }.isDefined === true

        val userById = members.+:(owner).map(u => u.id.get -> u).toMap

        implicit def membershipOrdering = new Ordering[OrganizationMembership] {
          def compare(x: OrganizationMembership, y: OrganizationMembership): Int = {
            if (x.userId == owner.id.get) -1
            else if (y.userId == owner.id.get) +1
            else userById(x.userId).fullName.compareTo(userById(y.userId).fullName)
          }
        }

        sortedMembers.exists(_.userId == owner.id.get) === true
        sortedMembers === allMembers.sorted
      }
    }

    "get members by role" in {
      withDb() { implicit injector =>
        val (org, owner, adminsExpected, membersExpected) = db.readWrite { implicit session =>
          val owner = user().withName("Zyxwv", "Utsr").saved
          val admins = Seq(user().withName("Aaron", "Aaronson"), user().withName("Barry", "Barnes")).map(_.saved)
          val members = Seq(user().withName("Carl", "Carson"), user().withName("Carl", "Junior")).map(_.saved)
          val org = organization().withOwner(owner).withAdmins(admins).withMembers(members).saved
          (org, owner, admins, members)
        }

        val adminsActual = db.readOnlyMaster { implicit session => orgMembershipRepo.getByRole(org.id.get, OrganizationRole.ADMIN) }
        adminsActual.toSet === (adminsExpected :+ owner).map(_.id.get).toSet

        val membersActual = db.readOnlyMaster { implicit session => orgMembershipRepo.getByRole(org.id.get, OrganizationRole.MEMBER) }
        membersActual.toSet === membersExpected.map(_.id.get).toSet
      }
    }
  }
}
