package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.payments.{ PlanManagementCommander, PaidPlan, DollarAmount, BillingCycle }

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

        inject[PlanManagementCommander].createNewPlan(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))

        implicit val context = HeimdalContext.empty

        val user = db.readWrite { implicit session => UserFactory.user().withName("Teeny", "Tiny").saved }

        val createRequest = OrganizationCreateRequest(requesterId = user.id.get, OrganizationInitialValues(name = "Kifi"))
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
          val publicLibs = LibraryFactory.libraries(10).map(_.withOwner(owner).withVisibility(LibraryVisibility.PUBLISHED).withOrganizationIdOpt(Some(org.id.get))).saved
          val orgLibs = LibraryFactory.libraries(20).map(_.withOwner(owner).withVisibility(LibraryVisibility.ORGANIZATION).withOrganizationIdOpt(Some(org.id.get))).saved
          val deletedLibs = LibraryFactory.libraries(15).map(_.withOwner(owner).withVisibility(LibraryVisibility.ORGANIZATION).withOrganizationIdOpt(Some(org.id.get))).saved.map(_.deleted)
          (org, owner, nonMember, publicLibs, orgLibs, deletedLibs)
        }

        val orgCommander = inject[OrganizationCommander]
        val ownerVisibleLibraries = orgCommander.getOrganizationLibrariesVisibleToUser(org.id.get, Some(owner.id.get), offset = Offset(0), limit = Limit(100))
        val randoVisibleLibraries = orgCommander.getOrganizationLibrariesVisibleToUser(org.id.get, Some(nonMember.id.get), offset = Offset(0), limit = Limit(100))
        val nooneVisibleLibraries = orgCommander.getOrganizationLibrariesVisibleToUser(org.id.get, None, offset = Offset(0), limit = Limit(100))

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

        inject[PlanManagementCommander].createNewPlan(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))

        implicit val context = HeimdalContext.empty

        val users = db.readWrite { implicit session => UserFactory.users(3).saved }

        val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = users(1).id.get, role = OrganizationRole.MEMBER))
        }

        // Random non-members shouldn't be able to modify the org
        val nonmemberModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = users(2).id.get,
          modifications = OrganizationModifications(name = Some("User 42 Rules!")))
        val nonmemberModifyResponse = orgCommander.modifyOrganization(nonmemberModifyRequest)
        nonmemberModifyResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Neither should a generic member
        val memberModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = users(1).id.get,
          modifications = OrganizationModifications(name = Some("User 2 Rules!")))
        val memberModifyResponse = orgCommander.modifyOrganization(memberModifyRequest)
        memberModifyResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // An owner can do whatever they want
        val ownerModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = users(0).id.get,
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

        inject[PlanManagementCommander].createNewPlan(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))

        implicit val context = HeimdalContext.empty

        val users = db.readWrite { implicit session => UserFactory.users(3).saved }

        val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = users(1).id.get, role = OrganizationRole.MEMBER))
        }

        val memberInviteMember = OrganizationMembershipAddRequest(orgId = org.id.get, requesterId = users(1).id.get, targetId = users(2).id.get, newRole = OrganizationRole.MEMBER)

        // By default, Organizations do not allow members to invite other members
        val try1 = orgMembershipCommander.addMembership(memberInviteMember)
        try1 === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // An owner can change the base permissions so that members CAN do this
        val betterBasePermissions = org.basePermissions.modified(OrganizationRole.MEMBER, added = Set(OrganizationPermission.INVITE_MEMBERS), removed = Set())
        val orgModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = users(0).id.get,
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
        allMembers.map(_.userId) === users.map(_.id.get).toSet
      }
    }

    "delete an organization" in {
      withDb(modules: _*) { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        inject[PlanManagementCommander].createNewPlan(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))

        implicit val context = HeimdalContext.empty

        val users = db.readWrite { implicit session => UserFactory.users(4).saved }

        val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = users(1).id.get, role = OrganizationRole.ADMIN))
          orgMembershipRepo.save(org.newMembership(userId = users(2).id.get, role = OrganizationRole.MEMBER))
        }

        // Random non-members shouldn't be able to delete the org
        val nonmemberDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = users(3).id.get)
        val nonmemberDeleteResponse = orgCommander.deleteOrganization(nonmemberDeleteRequest)
        nonmemberDeleteResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Neither should a generic member
        val memberDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = users(2).id.get)
        val memberDeleteResponse = orgCommander.deleteOrganization(memberDeleteRequest)
        memberDeleteResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Even an owner can't, if they aren't the "original" owner
        val ownerDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = users(1).id.get)
        val ownerDeleteResponse = orgCommander.deleteOrganization(ownerDeleteRequest)
        ownerDeleteResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // The OG owner can do whatever they want
        val trueOwnerDeleteRequest = OrganizationDeleteRequest(orgId = org.id.get, requesterId = users(0).id.get)
        val trueOwnerDeleteResponse = orgCommander.deleteOrganization(trueOwnerDeleteRequest)
        trueOwnerDeleteResponse must haveClass[Right[OrganizationDeleteRequest, Organization]]
        trueOwnerDeleteResponse.right.get.request === trueOwnerDeleteRequest

        db.readOnlyMaster { implicit session =>
          handleCommander.getByHandle(org.handle) must beNone
          orgRepo.get(org.id.get).state === OrganizationStates.INACTIVE
          orgMembershipRepo.getAllByOrgId(org.id.get).size === 0
        }
        1 === 1
      }
    }
    "transfer ownership of an organization" in {
      withDb(modules: _*) { implicit injector =>
        val orgRepo = inject[OrganizationRepo]
        val orgCommander = inject[OrganizationCommander]
        val orgMembershipRepo = inject[OrganizationMembershipRepo]

        inject[PlanManagementCommander].createNewPlan(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))

        implicit val context = HeimdalContext.empty

        val users = db.readWrite { implicit session => UserFactory.users(5).saved }

        val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
        val createResponse = orgCommander.createOrganization(createRequest)
        createResponse must haveClass[Right[OrganizationFail, OrganizationCreateResponse]]
        val org = createResponse.right.get.newOrg

        db.readWrite { implicit session =>
          orgMembershipRepo.save(org.newMembership(userId = users(1).id.get, role = OrganizationRole.ADMIN))
          orgMembershipRepo.save(org.newMembership(userId = users(2).id.get, role = OrganizationRole.MEMBER))
        }

        // Random non-members shouldn't be able to delete the org
        val nonmemberTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = users(3).id.get, newOwner = users(4).id.get)
        val nonmemberTransferResponse = orgCommander.transferOrganization(nonmemberTransferRequest)
        nonmemberTransferResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Neither should a generic member
        val memberTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = users(2).id.get, newOwner = users(4).id.get)
        val memberTransferResponse = orgCommander.transferOrganization(memberTransferRequest)
        memberTransferResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // Even an owner can't, if they aren't the "original" owner
        val ownerTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = users(1).id.get, newOwner = users(4).id.get)
        val ownerTransferResponse = orgCommander.transferOrganization(ownerTransferRequest)
        ownerTransferResponse === Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        // The OG owner can do whatever they want
        val trueOwnerTransferRequest = OrganizationTransferRequest(orgId = org.id.get, requesterId = users(0).id.get, newOwner = users(2).id.get)
        val trueOwnerTransferResponse = orgCommander.transferOrganization(trueOwnerTransferRequest)
        trueOwnerTransferResponse must haveClass[Right[OrganizationTransferRequest, Organization]]
        trueOwnerTransferResponse.right.get.request === trueOwnerTransferRequest

        val (modifiedOrg, newOwnerMembership) = db.readOnlyMaster { implicit session =>
          (orgRepo.get(org.id.get), orgMembershipRepo.getByOrgIdAndUserId(org.id.get, users(2).id.get).get)
        }
        modifiedOrg.state === OrganizationStates.ACTIVE
        modifiedOrg.ownerId === users(2).id.get
        newOwnerMembership.role === OrganizationRole.ADMIN
      }
    }
  }
}
