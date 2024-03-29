package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.WatchableExecutionContext
import com.keepit.common.mail.EmailAddress
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

import scala.util.Random

class OrganizationMembershipCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  "OrganizationMembershipCommander" should {
    "be able to list org members" in {
      "get memberships by organization id" in {
        withDb() { implicit injector =>
          val orgRepo = inject[OrganizationRepo]
          val orgMembershipRepo = inject[OrganizationMembershipRepo]
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val users = UserFactory.users(20).saved
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(users).withName("Luther Corp.").saved
            (org, owner)
          }

          val orgMembershipCommander = inject[OrganizationMembershipCommander]
          val failOrMembers = orgMembershipCommander.getMembersAndUniqueInvitees(org.id.get, owner.id, Offset(0), Limit(50), includeInvitees = true)
          failOrMembers must beRight
          failOrMembers.right.get must haveLength(21)
        }
      }

      "page results" in {
        withDb() { implicit injector =>
          val orgRepo = inject[OrganizationRepo]
          val orgMembershipRepo = inject[OrganizationMembershipRepo]
          val orgInviteRepo = inject[OrganizationInviteRepo]

          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val users = UserFactory.users(19).saved
            val invitees = Seq.fill(10)(EmailAddress(RandomStringUtils.randomAlphabetic(10) + "@kifi.com"))
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(users).withInvitedEmails(invitees).withName("Luther Corp.").saved
            (org, owner)
          }
          val orgId = org.id.get

          val orgMembershipCommander = inject[OrganizationMembershipCommander]
          // limit by count
          val failOrMembers1 = orgMembershipCommander.getMembersAndUniqueInvitees(orgId, owner.id, Offset(0), Limit(10), includeInvitees = true)
          failOrMembers1 must beRight
          failOrMembers1.right.get must haveLength(10)

          // limit with offset
          val failOrMembers2 = orgMembershipCommander.getMembersAndUniqueInvitees(orgId, owner.id, Offset(17), Limit(10), includeInvitees = true)
          failOrMembers2 must beRight

          val membersLimitedByOffset = failOrMembers2.right.get
          membersLimitedByOffset.take(3).foreach(_.member.isLeft === true)
          membersLimitedByOffset.drop(3).take(7).foreach(_.member.isRight === true)
          membersLimitedByOffset.length === 10
        }
      }
      "sort the members correctly" in {
        withDb() { implicit injector =>
          val (org, memberships, users) = db.readWrite { implicit s =>
            val users = Random.shuffle(UserFactory.users(100).saved)
            val (owner, rest) = (users.head, users.tail)
            val (admins, members) = rest.splitAt(20)
            val org = OrganizationFactory.organization().withOwner(owner).withAdmins(admins).withMembers(members).saved
            val memberships = inject[OrganizationMembershipRepo].getAllByOrgId(org.id.get)
            (org, memberships, users)
          }

          val usersById = users.map(u => u.id.get -> u).toMap
          def metric(om: OrganizationMembership) = {
            val user = usersById(om.userId)
            (user.id.get != org.ownerId, user.firstName, user.lastName)
          }

          val canonical = memberships.toSeq.sortBy(metric)
          val extToIntMap = users.map(u => u.externalId -> u.id.get).toMap
          val failOrMembers = inject[OrganizationMembershipCommander].getMembersAndUniqueInvitees(org.id.get, users.head.id, Offset(10), Limit(50), includeInvitees = false)
          failOrMembers must beRight

          val members = failOrMembers.right.get.map(_.member.left.get)
          val expected = canonical.drop(10).take(50)
          members.map(bu => extToIntMap(bu.externalId)) must equalTo(expected.map(_.userId))
        }
      }
    }

    "add members to org" in {
      withDb() { implicit injector =>

        val (org, owner, user1, user2, rando) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val user1 = UserFactory.user().saved
          val user2 = UserFactory.user().saved
          val rando = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(owner).withName("Luther Corp.").saved
          (org, owner, user1, user2, rando)
        }
        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val ownerAddUser = OrganizationMembershipAddRequest(org.id.get, owner.id.get, user1.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(ownerAddUser).isRight === true

        // can't add someone who is already a member
        val memberAddMember = OrganizationMembershipAddRequest(org.id.get, user1.id.get, owner.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(memberAddMember) === Left(OrganizationFail.ALREADY_A_MEMBER)
      }
    }

    "modify members in org" in {
      withDb() { implicit injector =>
        val (org, owner, admin, member, rando) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val admin = UserFactory.user().saved
          val member = UserFactory.user().saved
          val rando = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(owner).withAdmins(Seq(admin)).withMembers(Seq(member)).saved
          (org, owner, admin, member, rando)
        }
        val ownerModMember = OrganizationMembershipModifyRequest(org.id.get, owner.id.get, member.id.get, OrganizationRole.ADMIN)
        orgMembershipCommander.modifyMembership(ownerModMember) must beRight

        // member is now an ADMIN, try to set him back to MEMBER
        val memberModOwner = OrganizationMembershipModifyRequest(org.id.get, admin.id.get, member.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.modifyMembership(memberModOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Try to modify a random user
        val memberModUser = OrganizationMembershipModifyRequest(org.id.get, admin.id.get, rando.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.modifyMembership(memberModOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS) // TODO: this should fail differently
      }
    }

    "remove members from org" in {
      withDb() { implicit injector =>
        val (org, owner, member, rando) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val member = UserFactory.user().saved
          val rando = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
          (org, owner, member, rando)
        }
        val memberDelOwner = OrganizationMembershipRemoveRequest(org.id.get, member.id.get, owner.id.get)
        orgMembershipCommander.removeMembership(memberDelOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberDelRando = OrganizationMembershipRemoveRequest(org.id.get, member.id.get, rando.id.get)
        orgMembershipCommander.removeMembership(memberDelRando) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val ownerDelMember = OrganizationMembershipRemoveRequest(org.id.get, owner.id.get, member.id.get)
        orgMembershipCommander.removeMembership(ownerDelMember) must beRight

        // there are futures running in the background that will blow up if the test ends and the db is dumped
        inject[WatchableExecutionContext].drain()

        1 === 1
      }
    }
    "let members leave the org" in {
      withDb() { implicit injector =>
        val orgMembershipCommander = inject[OrganizationMembershipCommander]
        val (org, owner, member) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val member = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
          (org, owner, member)
        }

        val ownerLeave = OrganizationMembershipRemoveRequest(org.id.get, owner.id.get, owner.id.get)
        orgMembershipCommander.removeMembership(ownerLeave) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberLeave = OrganizationMembershipRemoveRequest(org.id.get, member.id.get, member.id.get)
        orgMembershipCommander.removeMembership(memberLeave) === Right(OrganizationMembershipRemoveResponse(memberLeave))

        // there are futures running in the background that will blow up if the test ends and the db is dumped
        inject[WatchableExecutionContext].drain()

        1 === 1
      }
    }
    "remove member permissions when they leave the org" in {
      withDb() { implicit injector =>
        val (org, owner, member) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val member = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).secret().saved
          (org, owner, member)
        }

        db.readOnlyMaster { implicit session =>
          permissionCommander.getOrganizationPermissions(org.id.get, Some(member.id.get)) must contain(OrganizationPermission.VIEW_ORGANIZATION)
        }

        val memberLeave = OrganizationMembershipRemoveRequest(org.id.get, member.id.get, member.id.get)
        orgMembershipCommander.removeMembership(memberLeave) === Right(OrganizationMembershipRemoveResponse(memberLeave))

        db.readOnlyMaster { implicit session =>
          permissionCommander.getOrganizationPermissions(org.id.get, Some(member.id.get)) must not contain (OrganizationPermission.VIEW_ORGANIZATION)
        }

        inject[WatchableExecutionContext].drain()
        1 === 1
      }
    }
  }
}
