package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._

class OrganizationCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )
  "organization commander" should {
    "create an organization" in {
      withDb(modules: _*) { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readOnlyMaster { implicit session => orgRepo.get(org.id.get) } === org
        val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
        memberships.size === 1
        memberships.head.userId === Id[User](1)
        memberships.head.role === OrganizationRole.ADMIN
      }
    }

    "grab an organization's visible libraries" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, nonMember, publicLibs, orgLibs, deletedLibs) = db.readWrite { implicit session =>
          val owner = UserFactory.user().withName("Owner", "McOwnerson").saved
          val nonMember = UserFactory.user().withName("Rando", "McRanderson").saved
          val org = OrganizationFactory.organization().withName("Test Org").withOwner(owner).saved
          val publicLibs = LibraryFactory.libraries(10).map(_.withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withOrganizationIdOpt(Some(org.id.get))).saved
          val orgLibs = LibraryFactory.libraries(20).map(_.withUser(owner).withVisibility(LibraryVisibility.ORGANIZATION).withOrganizationIdOpt(Some(org.id.get))).saved
          val deletedLibs = LibraryFactory.libraries(15).map(_.withUser(owner).withVisibility(LibraryVisibility.ORGANIZATION).withOrganizationIdOpt(Some(org.id.get))).saved.map(_.deleted)
          (org, owner, nonMember, publicLibs, orgLibs, deletedLibs)
        }

        val orgCommander = inject[OrganizationCommander]
        val ownerVisibleLibraries = orgCommander.getLibrariesVisibleToUser(org.id.get, Some(owner.id.get), offset = Offset(0), limit = Limit(100))
        val randoVisibleLibraries = orgCommander.getLibrariesVisibleToUser(org.id.get, Some(nonMember.id.get), offset = Offset(0), limit = Limit(100))
        val nooneVisibleLibraries = orgCommander.getLibrariesVisibleToUser(org.id.get, None, offset = Offset(0), limit = Limit(100))

        ownerVisibleLibraries.length === publicLibs.length + orgLibs.length
        randoVisibleLibraries.length === publicLibs.length
        nooneVisibleLibraries.length === publicLibs.length
        db.readOnlyMaster { implicit session =>
          inject[LibraryRepo].getBySpace(org.id.get, excludeStates = Set.empty).size === publicLibs.length + orgLibs.length + deletedLibs.length
        }
      }
    }

    "modify an organization" in {
      withDb(modules: _*) { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationInitialValues(name = "Kifi"))
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
          modifications = OrganizationModifications(name = Some("The view is nice from up here"), site = Some("www.kifi.com")))
        val ownerModifyResponse = orgCommander.modifyOrganization(ownerModifyRequest)
        ownerModifyResponse must haveClass[Right[OrganizationModifyRequest, Organization]]
        ownerModifyResponse.right.get.request === ownerModifyRequest
        ownerModifyResponse.right.get.modifiedOrg.name === "The view is nice from up here"
        ownerModifyResponse.right.get.modifiedOrg.site.get === "www.kifi.com"

        db.readOnlyMaster { implicit session => orgRepo.get(org.id.get) } === ownerModifyResponse.right.get.modifiedOrg
      }
    }

    "modify an organization's base permissions" in {
      withDb(modules: _*) { implicit injector =>
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]
        val orgMembershipCommander = inject[OrganizationMembershipCommander]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationInitialValues(name = "Kifi"))
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
        allMembers.size === 3
        allMembers.map(_.userId) === Set(Id[User](1), Id[User](2), Id[User](42))
      }
    }

    "delete an organization" in {
      withDb(modules: _*) { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.ADMIN))
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
        memberships.size === 0
      }
    }
    "transfer ownership of an organization" in {
      withDb(modules: _*) { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        val createRequest = OrganizationCreateRequest(requesterId = Id[User](1), OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = Id[User](2), role = OrganizationRole.ADMIN))
          orgMembershipRepo.save(org.newMembership(userId = Id[User](3), role = OrganizationRole.MEMBER))
        }

        // Random non-members shouldn't be able to delete the org
        val nonmemberTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = Id[User](42), newOwner = Id[User](43))
        val nonmemberTransferResponse = orgCommander.transferOrganization(nonmemberTransferRequest)
        nonmemberTransferResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Neither should a generic member
        val memberTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = Id[User](3), newOwner = Id[User](43))
        val memberTransferResponse = orgCommander.transferOrganization(memberTransferRequest)
        memberTransferResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Even an owner can't, if they aren't the "original" owner
        val ownerTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = Id[User](2), newOwner = Id[User](43))
        val ownerTransferResponse = orgCommander.transferOrganization(ownerTransferRequest)
        ownerTransferResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // The OG owner can do whatever they want
        val trueOwnerTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = Id[User](1), newOwner = Id[User](3))
        val trueOwnerTransferResponse = orgCommander.transferOrganization(trueOwnerTransferRequest)
        trueOwnerTransferResponse must haveClass[Right[OrganizationTransferRequest, Organization]]
        trueOwnerTransferResponse.right.get.request === trueOwnerTransferRequest

        val (modifiedOrg, newOwnerMembership) = db.readOnlyMaster { implicit session =>
          (orgRepo.get(org.id.get), orgMembershipRepo.getByOrgIdAndUserId(org.id.get, Id[User](3)).get)
        }
        modifiedOrg.state === OrganizationStates.ACTIVE
        modifiedOrg.ownerId === Id[User](3)
        newOwnerMembership.role === OrganizationRole.ADMIN
      }
    }
  }
}
