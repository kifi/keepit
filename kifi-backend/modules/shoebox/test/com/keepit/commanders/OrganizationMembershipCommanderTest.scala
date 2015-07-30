package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationMembershipCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  "OrganizationMembershipCommander" should {
    "get memberships by organization id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]
        val org = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val users = UserFactory.users(20).saved
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(users).withName("Luther Corp.").saved
          org
        }

        val orgMembershipCommander = inject[OrganizationMembershipCommander]
        orgMembershipCommander.getMembersAndInvitees(Id[Organization](0), Limit(10), Offset(0), includeInvitees = true).length === 0
        orgMembershipCommander.getMembersAndInvitees(org.id.get, Limit(50), Offset(0), includeInvitees = true).length === 21
      }
    }

    "and page results" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]
        val orgInviteRepo = inject[OrganizationInviteRepo]

        val org = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val users = UserFactory.users(19).saved
          val invitees = Seq.fill(10)(EmailAddress("ryan@kifi.com"))
          val org = OrganizationFactory.organization().withOwner(owner).withMembers(users).withInvitedEmails(invitees).withName("Luther Corp.").saved
          org
        }
        val orgId = org.id.get

        val orgMembershipCommander = inject[OrganizationMembershipCommander]
        // limit by count
        val membersLimitedByCount = orgMembershipCommander.getMembersAndInvitees(orgId, Limit(10), Offset(0), includeInvitees = true)
        membersLimitedByCount.length === 10

        // limit with offset
        val membersLimitedByOffset = orgMembershipCommander.getMembersAndInvitees(orgId, Limit(10), Offset(17), includeInvitees = true)
        membersLimitedByOffset.take(3).foreach(_.member.isLeft === true)
        membersLimitedByOffset.drop(3).take(7).foreach(_.member.isRight === true)
        membersLimitedByOffset.length === 10
      }
    }

    "add members to org" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

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

        // members cannot invite users by default.
        val memberAddUser = OrganizationMembershipAddRequest(org.id.get, user1.id.get, user2.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(memberAddUser) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // they definitely can't add owners
        val memberAddUserAsOwner = OrganizationMembershipAddRequest(org.id.get, user1.id.get, user2.id.get, OrganizationRole.ADMIN)
        orgMembershipCommander.addMembership(memberAddUserAsOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // random people can't add members either
        val randoAddUser = OrganizationMembershipAddRequest(org.id.get, rando.id.get, user2.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(randoAddUser) === Left(OrganizationFail.NOT_A_MEMBER)

        // can't add someone who is already a member
        val memberAddMember = OrganizationMembershipAddRequest(org.id.get, user1.id.get, owner.id.get, OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(memberAddMember) === Left(OrganizationFail.ALREADY_A_MEMBER)
      }
    }

    "modify members in org" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val org = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None, description = None, site = None))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](1), role = OrganizationRole.ADMIN))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.MEMBER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](3), role = OrganizationRole.MEMBER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](4), role = OrganizationRole.MEMBER))
          org
        }
        val orgId = org.id.get

        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val ownerModMember = OrganizationMembershipModifyRequest(orgId, Id[User](1), Id[User](2), OrganizationRole.ADMIN)
        orgMembershipCommander.modifyMembership(ownerModMember) must haveClass[Right[OrganizationFail, OrganizationMembershipModifyResponse]]

        // 2 is now an OWNER, try to set him back to MEMBER
        val memberModOwner = OrganizationMembershipModifyRequest(orgId, Id[User](3), Id[User](2), OrganizationRole.MEMBER)
        orgMembershipCommander.modifyMembership(memberModOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Try to modify a random user
        val memberModUser = OrganizationMembershipModifyRequest(orgId, Id[User](3), Id[User](42), OrganizationRole.MEMBER)
        orgMembershipCommander.modifyMembership(memberModOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS) // TODO: this should fail differently
      }
    }

    "remove members from org" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val org = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None, description = None, site = None))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](1), role = OrganizationRole.ADMIN))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.MEMBER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](3), role = OrganizationRole.MEMBER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](4), role = OrganizationRole.MEMBER))
          org
        }
        val orgId = org.id.get

        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val ownerDelMember = OrganizationMembershipRemoveRequest(orgId, Id[User](1), Id[User](2))
        orgMembershipCommander.removeMembership(ownerDelMember) === Right(OrganizationMembershipRemoveResponse(ownerDelMember))

        val memberDelOwner = OrganizationMembershipRemoveRequest(orgId, Id[User](3), Id[User](1))
        orgMembershipCommander.removeMembership(memberDelOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberDelNone = OrganizationMembershipRemoveRequest(orgId, Id[User](3), Id[User](2))
        orgMembershipCommander.removeMembership(memberDelNone) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
    "let members leave the org" in {
      withDb() { implicit injector =>
        val orgMembershipCommander = inject[OrganizationMembershipCommander]
        val (org, owner, member) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val member = UserFactory.user().saved
          val org = OrganizationFactory.organization().withName("Luther Corp.").withOwner(owner).withMembers(Seq(member)).saved
          (org, owner, member)
        }

        val ownerLeave = OrganizationMembershipRemoveRequest(org.id.get, owner.id.get, owner.id.get)
        orgMembershipCommander.removeMembership(ownerLeave) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberLeave = OrganizationMembershipRemoveRequest(org.id.get, member.id.get, member.id.get)
        orgMembershipCommander.removeMembership(memberLeave) === Right(OrganizationMembershipRemoveResponse(memberLeave))
      }
    }

  }
}
