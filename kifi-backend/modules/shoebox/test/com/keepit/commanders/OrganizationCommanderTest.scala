package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ ElementWithInternalIdNotFoundException, Id }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.payments._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{ Random, Try }

class OrganizationCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )
  "organization commander" should {
    "create an organization" in {
      "succeed for valid inputs" in {
        withDb(modules: _*) { implicit injector =>
          val orgRepo = inject[OrganizationRepo]
          val orgCommander = inject[OrganizationCommander]
          val orgMembershipRepo = inject[OrganizationMembershipRepo]

          val user = db.readWrite { implicit session =>
            PaidPlanFactory.paidPlan().saved
            UserFactory.user().withName("Teeny", "Tiny").saved
          }

          val createRequest = OrganizationCreateRequest(requesterId = user.id.get, OrganizationInitialValues(name = "Kifi"))
          val createResponse = orgCommander.createOrganization(createRequest)
          createResponse must beRight
          val org = createResponse.right.get.newOrg

          db.readOnlyMaster { implicit session => orgRepo.get(org.id.get) } === org
          val memberships = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
          memberships.size === 1
          memberships.head.userId === Id[User](1)
          memberships.head.role === OrganizationRole.ADMIN

          val avatar1 = inject[OrganizationAvatarCommander].getBestImageByOrgId(org.id.get, OrganizationAvatarConfiguration.defaultSize)
          avatar1.imagePath.path === "oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-200x200_cs.jpg"
          avatar1.width === 200
          avatar1.height === 200

          val avatar2 = inject[OrganizationAvatarCommander].getBestImageByOrgId(org.id.get, CropScaledImageSize.Tiny.idealSize)
          avatar2.imagePath.path === "oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-100x100_cs.jpg"
          avatar2.width === 100
          avatar2.height === 100
        }
      }
      "not hide valid exceptions" in {
        withDb(modules: _*) { implicit injector =>
          val createRequest = OrganizationCreateRequest(requesterId = Id[User](42), OrganizationInitialValues(name = "Kifi"))
          Try(inject[OrganizationCommander].createOrganization(createRequest)) must beFailedTry
        }
      }
    }

    "grab an organization's visible libraries" in {
      "handle visibility correctly for members/nonmembers" in {
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
            inject[LibraryRepo].getBySpace(org.id.get, excludeState = None).size === publicLibs.length + orgLibs.length + deletedLibs.length
          }
        }
      }
    }

    "modify an organization" in {
      "handle modify permissions correctly" in {
        withDb(modules: _*) { implicit injector =>
          val orgRepo = inject[OrganizationRepo]
          val orgCommander = inject[OrganizationCommander]
          val orgMembershipRepo = inject[OrganizationMembershipRepo]

          val users = db.readWrite { implicit session =>
            PaidPlanFactory.paidPlan().saved
            UserFactory.users(3).saved
          }

          val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
          val createResponse = orgCommander.createOrganization(createRequest)
          createResponse must beRight
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
          ownerModifyResponse must beRight
          ownerModifyResponse.right.get.request === ownerModifyRequest
          ownerModifyResponse.right.get.modifiedOrg.name === "The view is nice from up here"
          ownerModifyResponse.right.get.modifiedOrg.site.get === "www.kifi.com"

          db.readOnlyMaster { implicit session => orgRepo.get(org.id.get) } === ownerModifyResponse.right.get.modifiedOrg
        }
      }

      "modify an organization's base permissions" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, member, rando1, rando2) = db.readWrite { implicit session =>
            val Seq(owner, member, rando1, rando2) = UserFactory.users(4).saved
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
            (org, owner, member, rando1, rando2)
          }

          // Member adds rando, succeeds because of default invite permissions
          val memberAddRando1 = OrganizationMembershipAddRequest(orgId = org.id.get, requesterId = member.id.get, targetId = rando1.id.get, newRole = OrganizationRole.MEMBER)
          orgMembershipCommander.addMembership(memberAddRando1) must beRight

          // The owner decides that members are too irresponsible and should not invite others

          // An owner can change the base permissions so that members CANNOT do this
          val permissionsDiff = PermissionsDiff.justRemove(Some(OrganizationRole.MEMBER) -> Set(OrganizationPermission.INVITE_MEMBERS))
          val orgModifyRequest = OrganizationModifyRequest(orgId = org.id.get, requesterId = owner.id.get,
            modifications = OrganizationModifications(permissionsDiff = Some(permissionsDiff)))
          val orgModifyResponse = orgCommander.modifyOrganization(orgModifyRequest)
          orgModifyResponse must beRight

          orgModifyResponse.right.get.request === orgModifyRequest
          orgModifyResponse.right.get.modifiedOrg.basePermissions === org.basePermissions.applyPermissionsDiff(permissionsDiff)
          orgModifyResponse.right.get.modifiedOrg.basePermissions.forRole(OrganizationRole.MEMBER) === Organization.defaultBasePermissions.forRole(OrganizationRole.MEMBER) - OrganizationPermission.INVITE_MEMBERS

          // Now the member should not be able to invite others
          val memberAddRando2 = OrganizationMembershipAddRequest(orgId = org.id.get, requesterId = member.id.get, targetId = rando2.id.get, newRole = OrganizationRole.MEMBER)
          orgMembershipCommander.addMembership(memberAddRando2) must beLeft

          val allMembers = db.readOnlyMaster { implicit session => orgMembershipRepo.getAllByOrgId(org.id.get) }
          allMembers.size === 3
          allMembers.map(_.userId) === Set(owner, member, rando1).map(_.id.get)
        }
      }
    }

    "delete an organization" in {
      "handle permissions correctly" in {
        withDb(modules: _*) { implicit injector =>
          val orgRepo = inject[OrganizationRepo]
          val orgCommander = inject[OrganizationCommander]
          val orgMembershipRepo = inject[OrganizationMembershipRepo]

          val users = db.readWrite { implicit session =>
            PaidPlanFactory.paidPlan().saved
            UserFactory.users(4).saved
          }

          val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
          val createResponse = orgCommander.createOrganization(createRequest)
          createResponse must beRight
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
          trueOwnerDeleteResponse must beRight
          trueOwnerDeleteResponse.right.get.request === trueOwnerDeleteRequest
        }
      }
      "properly delete an organization" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, members, orgLibs, personalLibs) = db.readWrite { implicit session =>
            val users = Random.shuffle(UserFactory.users(10).saved)
            val (owner, members) = (users.head, users.tail)
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(members).saved
            val orgLibs = users.map { user => user.id.get -> LibraryFactory.libraries(3).map(_.withOwner(user).withOrganization(org)).saved.toSet }.toMap
            val personalLibs = users.map { user => user.id.get -> LibraryFactory.libraries(3).map(_.withOwner(user)).saved.toSet }.toMap
            (org, owner, members, orgLibs, personalLibs)
          }

          val users = members.toSet + owner

          db.readOnlyMaster { implicit session =>
            handleCommander.getByHandle(org.handle) must beSome
            orgRepo.get(org.id.get).state === OrganizationStates.ACTIVE
            orgMembershipRepo.getAllByOrgId(org.id.get).map(_.userId) === users.map(_.id.get)
            libraryRepo.getBySpace(org.id.get) === orgLibs.values.flatten.toSet
            for (u <- users) {
              libraryRepo.getAllByOwner(u.id.get).map(_.id.get).toSet === (orgLibs(u.id.get) ++ personalLibs(u.id.get)).map(_.id.get)
              libraryRepo.getBySpace(u.id.get).map(_.id.get) === personalLibs(u.id.get).map(_.id.get)
            }
          }

          val maybeResponse = orgCommander.deleteOrganization(OrganizationDeleteRequest(orgId = org.id.get, requesterId = owner.id.get))
          maybeResponse must beRight

          Await.result(maybeResponse.right.get.returningLibsFut, Duration.Inf)

          db.readOnlyMaster { implicit session =>
            handleCommander.getByHandle(org.handle) must beNone
            orgRepo.get(org.id.get).state === OrganizationStates.INACTIVE
            orgMembershipRepo.getAllByOrgId(org.id.get) === Set.empty
            libraryRepo.getBySpace(org.id.get) === Set.empty
            for (u <- users) {
              libraryRepo.getAllByOwner(u.id.get).map(_.id.get).toSet === (orgLibs(u.id.get) ++ personalLibs(u.id.get)).map(_.id.get)
              libraryRepo.getBySpace(u.id.get).map(_.id.get) === (orgLibs(u.id.get) ++ personalLibs(u.id.get)).map(_.id.get)
            }
          }
          1 === 1
        }
      }
    }
    "transfer ownership of an organization" in {
      "handle permissions correctly" in {
        withDb(modules: _*) { implicit injector =>
          val orgRepo = inject[OrganizationRepo]
          val orgCommander = inject[OrganizationCommander]
          val orgMembershipRepo = inject[OrganizationMembershipRepo]

          val users = db.readWrite { implicit session =>
            UserFactory.users(5).saved
          }

          val createRequest = OrganizationCreateRequest(requesterId = users(0).id.get, OrganizationInitialValues(name = "Kifi"))
          val createResponse = orgCommander.createOrganization(createRequest)
          createResponse must beRight
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
          trueOwnerTransferResponse must beRight
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
}
