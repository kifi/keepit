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

  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule()
  )

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val owner = UserFactory.user().withName("Elon", "Musk").saved
      val member = UserFactory.user().withName("Sergey", "Brin").saved
      val admin = UserFactory.user().withName("Larry", "Page").saved
      val org = OrganizationFactory.organization().withName("Tesla").withOwner(owner).withMembers(Seq(member)).withAdmins(Seq(admin)).saved
      (org, owner, admin, member)
    }
  }

  "publish library permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManager = inject[PlanManagementCommander]
        val (org, owner, admin, member) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganization(org.id.get).withPlan(plan.id.get).withSetting(FeatureSetting(Feature.get("publish_libraries").get.name, "admin")).saved
          (plan, account)
        }

        // owner can create public libraries
        val libraryCommander = inject[LibraryCommander]
        val ownerCreateRequest = LibraryCreateRequest(name = "Alphabet Soup", slug = "alphabet", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(owner.id.get, org.id)))
        val ownerLibResponse = libraryCommander.createLibrary(ownerCreateRequest, owner.id.get)
        ownerLibResponse must beRight

        // admin can create public libraries
        val adminCreateRequest = LibraryCreateRequest(name = "Alphabetter Soup", slug = "alphabetter", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(admin.id.get, org.id)))
        val adminLibResponse = libraryCommander.createLibrary(adminCreateRequest, admin.id.get)
        adminLibResponse must beRight

        // member cannot create public libraries
        val memberCreateRequest1 = LibraryCreateRequest(name = "Alphabest Soup", slug = "alphabest", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(member.id.get, org.id)))
        val memberLibResponse1 = libraryCommander.createLibrary(memberCreateRequest1, member.id.get)
        memberLibResponse1 must beLeft

        val memberCreateRequest2 = LibraryCreateRequest(name = "Alphabest Soup", slug = "alphabest", visibility = LibraryVisibility.SECRET, space = Some(LibrarySpace(member.id.get, org.id)))
        val memberLibResponse2 = libraryCommander.createLibrary(memberCreateRequest2, member.id.get)
        memberLibResponse2 must beRight
        val library = memberLibResponse2.right.get

        // member cannot modify an org library to be public
        val memberModifyRequest1 = LibraryModifyRequest(visibility = Some(LibraryVisibility.PUBLISHED))
        val memberLibResponse3 = libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest1)
        memberLibResponse3 must beLeft

        // admins can alter feature settings
        planManager.setAccountFeatureSettings(org.id.get, admin.id.get, FeatureSetting.alterSetting(account.featureSettings, FeatureSetting(Feature.get("publish_libraries").get.name, "member")))

        val memberModifyRequest2 = LibraryModifyRequest(visibility = Some(LibraryVisibility.PUBLISHED))
        val memberLibResponse4 = libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest2)
        memberLibResponse4 must beRight
      }
    }
  }

  "invite member permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManager = inject[PlanManagementCommander]
        val (org, owner, admin, member) = setup()

        val feature = Feature.get("invite_members").get

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganization(org.id.get).withPlan(plan.id.get).withSetting(FeatureSetting(feature.name, feature.options.find(_ == "admin").get)).saved
          (plan, account)
        }

        val invitees = db.readWrite { implicit session => UserFactory.users(3).saved }

        val orgInviteCommander = inject[OrganizationInviteCommander]
        val ownerInviteRequest = OrganizationInviteSendRequest(org.id.get, owner.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(0).id.get))
        val ownerInviteResponse = Await.result(orgInviteCommander.inviteToOrganization(ownerInviteRequest), Duration(5, "seconds"))
        ownerInviteResponse must beRight

        val adminInviteRequest = OrganizationInviteSendRequest(org.id.get, admin.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(1).id.get))
        val adminInviteResponse = Await.result(orgInviteCommander.inviteToOrganization(adminInviteRequest), Duration(5, "seconds"))
        adminInviteResponse must beRight

        val memberInviteRequest1 = OrganizationInviteSendRequest(org.id.get, member.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(2).id.get))
        val memberInviteResponse1 = Await.result(orgInviteCommander.inviteToOrganization(memberInviteRequest1), Duration(5, "seconds"))
        memberInviteResponse1 must beLeft

        // admins can alter feature settings
        planManager.setAccountFeatureSettings(org.id.get, admin.id.get, FeatureSetting.alterSetting(account.featureSettings, FeatureSetting(feature.name, feature.options.find(_ == "member").get)))

        val memberInviteRequest2 = OrganizationInviteSendRequest(org.id.get, member.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(2).id.get))
        val memberInviteResponse2 = Await.result(orgInviteCommander.inviteToOrganization(memberInviteRequest2), Duration(5, "seconds"))
        memberInviteResponse2 must beRight
      }
    }
  }

  "edit library permissions" should {
    "be configurable" in {

      withDb(modules: _*) { implicit injector =>

        val planManager = inject[PlanManagementCommander]
        val (org, owner, admin, member) = setup()

        val feature = Feature.get("force_edit_libraries").get

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganization(org.id.get).withPlan(plan.id.get).withSetting(FeatureSetting(feature.name, feature.options.find(_ == "admin").get)).saved
          (plan, account)
        }

        val library = db.readWrite { implicit session => LibraryFactory.library().withOwner(owner).withOrganization(org).saved }

        val libraryCommander = inject[LibraryCommander]

        val ownerModifyRequest = LibraryModifyRequest(name = Some("Elon's Main Library"))
        val adminModifyRequest = LibraryModifyRequest(name = Some("Larry's Main Library"))
        val memberModifyRequest = LibraryModifyRequest(name = Some("Sergey's Main Library"))

        libraryCommander.modifyLibrary(library.id.get, owner.id.get, ownerModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, admin.id.get, adminModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest) must beLeft
      }
    }
  }

  "edit organization permission" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManagementCommander = inject[PlanManagementCommander]
        val (org, owner, admin, member) = setup()

        val feature = Feature.get("edit_organization").get

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganization(org.id.get).withPlan(plan.id.get).withSetting(FeatureSetting(Feature.get("edit_organization").get.name, "admin")).saved
          (plan, account)
        }

        val organizationCommander = inject[OrganizationCommander]

        val ownerModifyRequest = OrganizationModifyRequest(owner.id.get, org.id.get, OrganizationModifications(name = Some("Tesla Inc.")))
        val adminModifyRequest = OrganizationModifyRequest(admin.id.get, org.id.get, OrganizationModifications(name = Some("Tesla Enterprises")))
        val memberModifyRequest = OrganizationModifyRequest(member.id.get, org.id.get, OrganizationModifications(name = Some("Tesla United")))

        organizationCommander.modifyOrganization(ownerModifyRequest) must beRight
        organizationCommander.modifyOrganization(adminModifyRequest) must beRight
        val memberResponse = organizationCommander.modifyOrganization(memberModifyRequest)
        memberResponse must beLeft
        memberResponse.left.get must equalTo(OrganizationFail.INSUFFICIENT_PERMISSIONS)

        planManagementCommander.setAccountFeatureSettings(org.id.get, owner.id.get, FeatureSetting.alterSetting(account.featureSettings, FeatureSetting(feature.name, feature.options.find(_ == "member").get)))

        organizationCommander.modifyOrganization(memberModifyRequest) must beRight
      }
    }
  }

}
