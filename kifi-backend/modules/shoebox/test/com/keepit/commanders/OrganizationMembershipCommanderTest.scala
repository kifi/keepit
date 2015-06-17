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
        val orgId = Id[Organization](1)
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        db.readWrite { implicit session =>
          for { i <- 1 to 20 } yield {
            val userId = UserFactory.user().withId(Id[User](i)).saved.id.get
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = userId, role = OrganizationRole.MEMBER))
          }
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]
        orgMemberCommander.getMembersAndInvitees(Id[Organization](0), Limit(10), Offset(0), true).length === 0
        orgMemberCommander.getMembersAndInvitees(orgId, Limit(50), Offset(0), true).length === 20
      }
    }

    "and page results" in {
      withDb() { implicit injector =>
        val orgId = Id[Organization](1)
        val orgMemberRepo = inject[OrganizationMembershipRepo]
        val orgInviteRepo = inject[OrganizationInviteRepo]
        db.readWrite { implicit session =>
          for { i <- 1 to 20 } yield {
            val userId = UserFactory.user().withId(Id[User](i)).saved.id.get
            orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = userId, role = OrganizationRole.MEMBER))
            orgInviteRepo.save(OrganizationInvite(organizationId = orgId, inviterId = Id[User](1), role = OrganizationRole.MEMBER, emailAddress = Some(EmailAddress("colin@kifi.com"))))
          }
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]
        // limit by count
        val membersLimitedByCount = orgMemberCommander.getMembersAndInvitees(orgId, Limit(10), Offset(0), true)
        membersLimitedByCount.length === 10

        // limit with offset
        val membersLimitedByOffset = orgMemberCommander.getMembersAndInvitees(orgId, Limit(10), Offset(17), true)
        membersLimitedByOffset.take(3).foreach(_.member.isLeft === true)
        membersLimitedByOffset.drop(3).take(7).foreach(_.member.isRight === true)
        membersLimitedByOffset.length === 10
      }
    }

    "add members to org" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val orgId = Id[Organization](1)
        val ownerId = Id[User](1)

        db.readWrite { implicit session =>
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = ownerId, role = OrganizationRole.OWNER))
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]

        val ownerAddUser = OrganizationMembershipAddRequest(orgId, ownerId, Id[User](2), OrganizationRole.MEMBER)
        orgMemberCommander.addMembership(ownerAddUser) === Right(OrganizationMembershipAddResponse(ownerAddUser))

        val memberAddUser = OrganizationMembershipAddRequest(orgId, Id[User](2), Id[User](3), OrganizationRole.MEMBER)
        orgMemberCommander.addMembership(memberAddUser) === Right(OrganizationMembershipAddResponse(memberAddUser))

        val memberAddUserAsOwner = OrganizationMembershipAddRequest(orgId, Id[User](2), Id[User](5), OrganizationRole.OWNER)
        orgMemberCommander.addMembership(memberAddUserAsOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val noneAddUser = OrganizationMembershipAddRequest(orgId, Id[User](42), Id[User](4), OrganizationRole.MEMBER)
        orgMemberCommander.addMembership(noneAddUser) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberAddMember = OrganizationMembershipAddRequest(orgId, Id[User](3), Id[User](1), OrganizationRole.MEMBER)
        orgMemberCommander.addMembership(memberAddMember) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS) // TODO: this should fail differently
      }
    }

    "modify members in org" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val orgId = Id[Organization](1)

        db.readWrite { implicit session =>
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](1), role = OrganizationRole.OWNER))
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](2), role = OrganizationRole.MEMBER))
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](3), role = OrganizationRole.MEMBER))
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](4), role = OrganizationRole.MEMBER))
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]

        val ownerModMember = OrganizationMembershipModifyRequest(orgId, Id[User](1), Id[User](2), OrganizationRole.OWNER)
        orgMemberCommander.modifyMembership(ownerModMember) === Right(OrganizationMembershipModifyResponse(ownerModMember))

        // 2 is now an OWNER, try to set him back to MEMBER
        val memberModOwner = OrganizationMembershipModifyRequest(orgId, Id[User](3), Id[User](2), OrganizationRole.MEMBER)
        orgMemberCommander.modifyMembership(memberModOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Try to modify a random user
        val memberModUser = OrganizationMembershipModifyRequest(orgId, Id[User](3), Id[User](42), OrganizationRole.MEMBER)
        orgMemberCommander.modifyMembership(memberModOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS) // TODO: this should fail differently
      }
    }

    "remove members from org" in {
      withDb() { implicit injector =>
        val orgMemberRepo = inject[OrganizationMembershipRepo]

        val orgId = Id[Organization](1)

        db.readWrite { implicit session =>
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](1), role = OrganizationRole.OWNER))
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](2), role = OrganizationRole.MEMBER))
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](3), role = OrganizationRole.MEMBER))
          orgMemberRepo.save(OrganizationMembership(organizationId = orgId, userId = Id[User](4), role = OrganizationRole.MEMBER))
        }

        val orgMemberCommander = inject[OrganizationMembershipCommander]

        val ownerDelMember = OrganizationMembershipRemoveRequest(orgId, Id[User](1), Id[User](2))
        orgMemberCommander.removeMembership(ownerDelMember) === Right(OrganizationMembershipRemoveResponse(ownerDelMember))

        val memberDelOwner = OrganizationMembershipRemoveRequest(orgId, Id[User](3), Id[User](1))
        orgMemberCommander.removeMembership(memberDelOwner) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        val memberDelNone = OrganizationMembershipRemoveRequest(orgId, Id[User](3), Id[User](2))
        orgMemberCommander.removeMembership(memberDelNone) === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }

  }
}
