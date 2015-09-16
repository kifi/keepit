package com.keepit.payments

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.slick.Database
import com.keepit.model.LibrarySpace.UserSpace
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.actor.TestKitSupport
import com.keepit.commanders.{ OrganizationInviteCommander, LibraryCommander, OrganizationCommander }
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model.PaidAccountFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.heimdal.HeimdalContext

import org.specs2.mutable.SpecificationLike

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class PaidFeatureSettingsTest extends SpecificationLike with ShoeboxTestInjector {

  args(skipAll = true)

  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule()
  )

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val owner = UserFactory.user().withName("Elon", "Musk").saved
      val member = UserFactory.user().withName("Sergey", "Brin").saved
      val admin = UserFactory.user().withName("Larry", "Page").saved
      val org = OrganizationFactory.organization().withName("Tesla").withOwner(owner).withMembers(Seq(member, admin)).saved
      val paidPlan = PaidPlanFactory.paidPlan().saved
      val paidAccount = PaidAccountFactory.paidAccount().withOrganization(org.id.get).withPlan(paidPlan.id.get).saved
      (org, owner, admin, member, paidPlan, paidAccount)
    }
  }

  "library creation permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>

        val (org, owner, admin, member, paidPlan, paidAccount) = setup()

        val planManagementCommander = inject[PlanManagementCommander]

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().withFeatures(
            Set(
              PermissionFeature(
                PermissionFeatureNames.PUBLISH_LIBRARIES,
                editable = true,
                options = Seq(PermissionSetting(Some(OrganizationRole.MEMBER)), PermissionSetting(Some(OrganizationRole.ADMIN))),
                default = PermissionSetting(Some(OrganizationRole.MEMBER))
              )
            )
          ).saved
          val account = PaidAccountFactory.paidAccount().withOrganization(org.id.get).withPlan(plan.id.get).withSettings(
            Set(
              PermissionFeatureSetting(name = PermissionFeatureNames.PUBLISH_LIBRARIES, setting = PermissionSetting(Some(OrganizationRole.ADMIN)))
            )
          ).saved

          val newPermissions = FeatureSetting.toBasePermissions(account.featureSettings)

          val orgModifyRequest = OrganizationModifyRequest(owner.id.get, org.id.get, OrganizationModifications(permissionsDiff = Some(PermissionsDiff())))

          inject[OrganizationCommander].modifyOrganization(OrganizationModifyRequest(owner.id.get, org.id.get, OrganizationModifications(permissionsDiff = Some(PermissionsDiff()))))
          (plan, account)
        }

        // owner can create public libraries
        val libraryCommander = inject[LibraryCommander]
        val ownerCreateRequest = LibraryCreateRequest(name = "Alphabet Soup", slug = "alphabet", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(owner.id.get, org.id)))
        val ownerLibResponse = libraryCommander.createLibrary(ownerCreateRequest, owner.id.get)
        ownerLibResponse.isRight === true

        // admin can create public libraries
        val adminCreateRequest = LibraryCreateRequest(name = "Alphabetter Soup", slug = "alphabetter", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(admin.id.get, org.id)))
        val adminLibResponse = libraryCommander.createLibrary(adminCreateRequest, admin.id.get)
        adminLibResponse.isRight === true

        // member cannot create public libraries
        val memberCreateRequest = LibraryCreateRequest(name = "Alphabest Soup", slug = "alphabest", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(member.id.get, org.id)))
        val memberLibResponse = libraryCommander.createLibrary(memberCreateRequest, member.id.get)
        memberLibResponse.isRight === false
      }
    }
  }

  "invite member permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>

        //
        // configure PaidFeature set and PaidFeatureSettings
        //

        val (org, owner, admin, member, paidPlan, paidAccount) = setup()

        val invitees = db.readWrite { implicit session => UserFactory.users(3).saved }

        val orgInviteCommander = inject[OrganizationInviteCommander]
        val ownerInviteRequest = OrganizationInviteSendRequest(org.id.get, owner.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(0).id.get))
        val ownerInviteResponse = Await.result(orgInviteCommander.inviteToOrganization(ownerInviteRequest), Duration(5, "seconds"))
        ownerInviteResponse must beRight

        val adminInviteRequest = OrganizationInviteSendRequest(org.id.get, admin.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(1).id.get))
        val adminInviteResponse = Await.result(orgInviteCommander.inviteToOrganization(adminInviteRequest), Duration(5, "seconds"))
        adminInviteResponse must beRight

        val memberInviteRequest = OrganizationInviteSendRequest(org.id.get, member.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(2).id.get))
        val memberInviteResponse = Await.result(orgInviteCommander.inviteToOrganization(memberInviteRequest), Duration(5, "seconds"))
        memberInviteResponse must beRight
      }
    }
  }

  "edit library permissions" should {
    "be configurable" in {

      withDb(modules: _*) { implicit injector =>

        //
        // configure PaidFeature set and PaidFeatureSettings
        //

        val (org, owner, admin, member, paidPlan, paidAccount) = setup()

        val library = db.readWrite { implicit session => LibraryFactory.library().withOwner(owner).withOrganization(org).saved }

        val libraryCommander = inject[LibraryCommander]

        val ownerModifyRequest = LibraryModifyRequest(name = Some("Elon's Main Library"))
        val adminModifyRequest = LibraryModifyRequest(name = Some("Larry's Main Library"))
        val memberModifyRequest = LibraryModifyRequest(name = Some("Sergey's Main Library"))

        libraryCommander.modifyLibrary(library.id.get, owner.id.get, ownerModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, admin.id.get, adminModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest) must beRight
      }
    }
  }

}
