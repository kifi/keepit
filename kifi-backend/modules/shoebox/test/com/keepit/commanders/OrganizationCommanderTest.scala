package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class OrganizationCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  "organization commander" should {
    "create an organization" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationModifications(name = Some("Kifi")))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readOnlyMaster { implicit session => orgRepo.get(org.id.get) } === org
        val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
        memberships.length === 1
        memberships.head.userId === Id[User](1)
        memberships.head.role === OrganizationRole.OWNER
      }
    }

    "modify an organization" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationModifications(name = Some("Kifi")))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.MEMBER))
        }

        // Random non-members shouldn't be able to modify the org
        val nonmemberModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = Id[User](42),
          modifications = OrganizationModifications(name = Some("User 42 Rules!")))
        val nonmemberModifyResponse = orgCommander.modifyOrganization(nonmemberModifyRequest)
        nonmemberModifyResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Neither should a generic member
        val memberModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = Id[User](2),
          modifications = OrganizationModifications(name = Some("User 2 Rules!")))
        val memberModifyResponse = orgCommander.modifyOrganization(memberModifyRequest)
        memberModifyResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // An owner can do whatever they want
        val ownerModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = Id[User](1),
          modifications = OrganizationModifications(name = Some("The view is nice from up here")))
        val ownerModifyResponse = orgCommander.modifyOrganization(ownerModifyRequest)
        ownerModifyResponse must haveClass[Right[OrganizationModifyRequest, Organization]]
        ownerModifyResponse.right.get.request === ownerModifyRequest
        ownerModifyResponse.right.get.modifiedOrg.name === "The view is nice from up here"

        db.readOnlyMaster { implicit session => orgRepo.get(org.id.get) } === ownerModifyResponse.right.get.modifiedOrg
      }
    }

    "modify an organization's base permissions" in {
      withDb() { implicit injector =>
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]
        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationModifications(name = Some("Kifi")))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.MEMBER))
        }

        val memberInviteMember = OrganizationMembershipAddRequest(orgId = org.id.get, requesterId = Id[User](2), targetId = Id[User](42), newRole = OrganizationRole.MEMBER)

        // By default, Organizations do not allow members to invite other members
        val try1 = orgMembershipCommander.addMembership(memberInviteMember)
        try1 === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // An owner can change the base permissions so that members CAN do this
        val betterBasePermissions = org.basePermissions.modified(OrganizationRole.MEMBER, added = Set(OrganizationPermission.INVITE_MEMBERS), removed = Set())
        val orgModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = Id[User](1),
          modifications = OrganizationModifications(basePermissions = Some(betterBasePermissions)))

        val orgModifyResponse = orgCommander.modifyOrganization(orgModifyRequest)
        orgModifyResponse must haveClass[Right[OrganizationFail, OrganizationModifyResponse]]
        orgModifyResponse.right.get.request === orgModifyRequest
        orgModifyResponse.right.get.modifiedOrg.basePermissions === betterBasePermissions

        // Now the member should be able to invite others
        val try2 = orgMembershipCommander.addMembership(memberInviteMember)
        try2 must haveClass[Right[OrganizationFail, OrganizationMembershipAddResponse]]

        val allMembers = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
        allMembers.length === 3
        allMembers.map(_.userId).sorted === Seq(Id[User](1), Id[User](2), Id[User](42))
      }
    }

    "delete an organization" in {
      withDb() { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationModifications(name = Some("Kifi")))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.OWNER))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](3), role = OrganizationRole.MEMBER))
        }

        // Random non-members shouldn't be able to delete the org
        val nonmemberDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = Id[User](42))
        val nonmemberDeleteResponse = orgCommander.deleteOrganization(nonmemberDeleteRequest)
        nonmemberDeleteResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Neither should a generic member
        val memberDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = Id[User](3))
        val memberDeleteResponse = orgCommander.deleteOrganization(memberDeleteRequest)
        memberDeleteResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Even an owner can't, if they aren't the "original" owner
        val ownerDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = Id[User](2))
        val ownerDeleteResponse = orgCommander.deleteOrganization(ownerDeleteRequest)
        ownerDeleteResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // The OG owner can do whatever they want
        val trueOwnerDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = Id[User](1))
        val trueOwnerDeleteResponse = orgCommander.deleteOrganization(trueOwnerDeleteRequest)
        trueOwnerDeleteResponse must haveClass[Right[OrganizationDeleteRequest, Organization]]
        trueOwnerDeleteResponse.right.get.request === trueOwnerDeleteRequest

        val (deactivatedOrg, memberships) = db.readOnlyMaster { implicit session =>
          (orgRepo.get(org.id.get), orgMembershipRepo.getAllByOrgId(org.id.get))
        }
        deactivatedOrg.state === OrganizationStates.INACTIVE
        memberships.length === 0
      }
    }
  }
}
