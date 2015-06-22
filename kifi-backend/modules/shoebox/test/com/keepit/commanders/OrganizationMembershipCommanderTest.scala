package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationMembershipCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  "organization membership commander" should {
    "get memberships by organization id" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]
        val org = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          for { i <- 1 to 20 } yield {
            val userId = UserFactory.user().withId(Id[User](i)).saved.id.get
            orgMembershipRepo.save(org.newMembership(userId = userId, role = OrganizationRole.MEMBER))
          }
          org
        }

        val orgMembershipCommander = inject[OrganizationMembershipCommander]
        orgMembershipCommander.getMembersAndInvitees(Id[Organization](0), Limit(10), Offset(0), includeInvitees = true).length === 0
        orgMembershipCommander.getMembersAndInvitees(org.id.get, Limit(50), Offset(0), includeInvitees = true).length === 20
      }
    }

    "and page results" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]
        val orgInviteRepo = inject[OrganizationInviteRepo]

        val org = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          for { i <- 1 to 20 } yield {
            val userId = UserFactory.user().withId(Id[User](i)).saved.id.get
            orgMembershipRepo.save(org.newMembership(userId, OrganizationRole.MEMBER))
            orgInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = Id[User](1), role = OrganizationRole.MEMBER, emailAddress = Some(EmailAddress("colin@kifi.com"))))
          }
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

        val org = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](1), role = OrganizationRole.OWNER))
          org
        }
        val orgId = org.id.get

        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val ownerAddUser = OrganizationMembershipAddRequest(orgId, Id[User](1), Id[User](2), OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(ownerAddUser) must haveClass[Right[OrganizationFail, OrganizationMembershipAddResponse]]

        val memberAddUser = OrganizationMembershipAddRequest(orgId, Id[User](2), Id[User](3), OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(memberAddUser) must haveClass[Right[OrganizationFail, OrganizationMembershipAddResponse]]

        val memberAddUserAsOwner = OrganizationMembershipAddRequest(orgId, Id[User](2), Id[User](5), OrganizationRole.OWNER)
        orgMembershipCommander.addMembership(memberAddUserAsOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val noneAddUser = OrganizationMembershipAddRequest(orgId, Id[User](42), Id[User](4), OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(noneAddUser) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberAddMember = OrganizationMembershipAddRequest(orgId, Id[User](3), Id[User](1), OrganizationRole.MEMBER)
        orgMembershipCommander.addMembership(memberAddMember) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS) // TODO: this should fail differently
      }
    }

    "modify members in org" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val org = db.readWrite { implicit session =>
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](1), role = OrganizationRole.OWNER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.MEMBER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](3), role = OrganizationRole.MEMBER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](4), role = OrganizationRole.MEMBER))
          org
        }
        val orgId = org.id.get

        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val ownerModMember = OrganizationMembershipModifyRequest(orgId, Id[User](1), Id[User](2), OrganizationRole.OWNER)
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
          val org = orgRepo.save(Organization(ownerId = Id[User](1), name = "Luther Corp.", handle = None))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](1), role = OrganizationRole.OWNER))
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

  }
}
