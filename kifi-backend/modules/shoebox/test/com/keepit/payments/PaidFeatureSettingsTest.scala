package com.keepit.payments

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.controllers.ext.ExtUserController
import com.keepit.controllers.mobile.{ MobileContactsController, MobileUserController, MobileOrganizationMembershipController }
import com.keepit.controllers.website.{ LibraryController, OrganizationMembershipController }
import com.keepit.model.LibrarySpace.UserSpace
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.actor.TestKitSupport
import com.keepit.commanders.{ KeepExportCommander, OrganizationInviteCommander, LibraryCommander, OrganizationCommander }
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model.PaidAccountFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.heimdal.HeimdalContext

import org.specs2.mutable.SpecificationLike
import play.api.libs.json.JsObject
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

class PaidFeatureSettingsTest extends SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule()
  )

  implicit val context = HeimdalContext.empty
  implicit def pubIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def planManagementCommander(implicit injector: Injector) = inject[PlanManagementCommander]

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val owner = UserFactory.user().saved
      val member = UserFactory.user().saved
      val admin = UserFactory.user().saved
      val nonMember = UserFactory.user().saved
      val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).withAdmins(Seq(admin)).saved
      (org, owner, admin, member, nonMember)
    }
  }

  "publish library permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, admin, member, _) = setup()

        // owner can create public libraries
        val ownerCreateRequest = LibraryInitialValues(name = "Alphabet Soup", slug = "alphabet", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(owner.id.get, org.id)))
        val ownerLibResponse = libraryCommander.createLibrary(ownerCreateRequest, owner.id.get)
        ownerLibResponse must beRight

        // admin can create public libraries
        val adminCreateRequest = LibraryInitialValues(name = "Alphabetter Soup", slug = "alphabetter", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(admin.id.get, org.id)))
        val adminLibResponse = libraryCommander.createLibrary(adminCreateRequest, admin.id.get)
        adminLibResponse must beRight

        // member cannot create public libraries
        val memberCreateRequest1 = LibraryInitialValues(name = "Alphabest Soup", slug = "alphabest", visibility = LibraryVisibility.PUBLISHED, space = Some(LibrarySpace(member.id.get, org.id)))
        val memberLibResponse1 = libraryCommander.createLibrary(memberCreateRequest1, member.id.get)
        memberLibResponse1 must beLeft

        val memberCreateRequest2 = LibraryInitialValues(name = "Alphabest Soup", slug = "alphabest", visibility = LibraryVisibility.SECRET, space = Some(LibrarySpace(member.id.get, org.id)))
        val memberLibResponse2 = libraryCommander.createLibrary(memberCreateRequest2, member.id.get)
        memberLibResponse2 must beRight
        val library = memberLibResponse2.right.get

        // member cannot modify an org library to be public
        val memberModifyRequest1 = LibraryModifications(visibility = Some(LibraryVisibility.PUBLISHED))
        val memberLibResponse3 = libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest1)
        memberLibResponse3 must beLeft

        // admins can alter feature settings
        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings.set(Feature.PublishLibraries -> Feature.PublishLibrariesSetting.MEMBERS))

        val memberModifyRequest2 = LibraryModifications(visibility = Some(LibraryVisibility.PUBLISHED))
        val memberLibResponse4 = libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest2)
        memberLibResponse4 must beRight
      }
    }
  }

  "invite member permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, admin, member, _) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
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
        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings.set(Feature.InviteMembers -> Feature.InviteMembersSetting.MEMBERS))

        val memberInviteRequest2 = OrganizationInviteSendRequest(org.id.get, member.id.get, targetEmails = Set.empty, targetUserIds = Set(invitees(2).id.get))
        val memberInviteResponse2 = Await.result(orgInviteCommander.inviteToOrganization(memberInviteRequest2), Duration(5, "seconds"))
        memberInviteResponse2 must beRight
      }
    }
  }

  "edit library permissions" should {
    "be configurable" in {

      withDb(modules: _*) { implicit injector =>
        val (org, owner, admin, member, _) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
          (plan, account)
        }

        val library = db.readWrite { implicit session => LibraryFactory.library().withOwner(owner).withOrganization(org).saved }

        val libraryCommander = inject[LibraryCommander]

        val ownerModifyRequest = LibraryModifications(name = Some("Elon's Main Library"))
        val adminModifyRequest = LibraryModifications(name = Some("Larry's Main Library"))
        val memberModifyRequest = LibraryModifications(name = Some("Sergey's Main Library"))

        libraryCommander.modifyLibrary(library.id.get, owner.id.get, ownerModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, admin.id.get, adminModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest) must beLeft

        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings.set(Feature.ForceEditLibraries -> Feature.ForceEditLibrariesSetting.MEMBERS))

        libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest) must beRight
      }
    }
  }

  "view members permissions" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val (org, owner, admin, member, nonMember) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
          (plan, account)
        }

        val testRoute = com.keepit.controllers.website.routes.OrganizationMembershipController.getMembers(Organization.publicId(org.id.get)).url
        val organizationMembershipController = inject[OrganizationMembershipController]
        val mobileOrganizationMembershipController = inject[MobileOrganizationMembershipController]

        // admins can see the org's members
        inject[FakeUserActionsHelper].setUser(admin)
        val adminRequest = FakeRequest("GET", testRoute)
        val adminResult = organizationMembershipController.getMembers(Organization.publicId(org.id.get), 0, 30)(adminRequest) // 30 is the max limit for this request handler
        status(adminResult) must equalTo(OK)

        // members can see the org's members
        inject[FakeUserActionsHelper].setUser(member)
        val memberRequest = FakeRequest("GET", testRoute)
        val memberResult = organizationMembershipController.getMembers(Organization.publicId(org.id.get), 0, 30)(memberRequest)
        status(memberResult) must equalTo(OK)

        // non-members cannot see the org's members
        inject[FakeUserActionsHelper].setUser(nonMember)
        val nonMemberRequest = FakeRequest("GET", testRoute)
        val nonMemberResult1 = organizationMembershipController.getMembers(Organization.publicId(org.id.get), 0, 30)(nonMemberRequest)
        status(nonMemberResult1) must equalTo(FORBIDDEN)
        (contentAsJson(nonMemberResult1) \ "error").as[String] must equalTo("insufficient_permissions")

        // testing mobile permissions
        val nonMemberMobileResult1 = mobileOrganizationMembershipController.getMembers(Organization.publicId(org.id.get), 0, 30)(nonMemberRequest)
        status(nonMemberMobileResult1) must equalTo(FORBIDDEN)
        (contentAsJson(nonMemberMobileResult1) \ "error").as[String] must equalTo("insufficient_permissions")

        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings.set(Feature.ViewMembers -> Feature.ViewMembersSetting.ANYONE))

        val nonMemberResult2 = organizationMembershipController.getMembers(Organization.publicId(org.id.get), 0, 30)(nonMemberRequest)
        status(nonMemberResult2) must equalTo(OK)

        val nonMemberMobileResult2 = mobileOrganizationMembershipController.getMembers(Organization.publicId(org.id.get), 0, 30)(nonMemberRequest)
        status(nonMemberMobileResult2) must equalTo(OK)
      }
    }
  }

  "edit organization permission" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManagementCommander = inject[PlanManagementCommander]
        val (org, owner, admin, member, nonMember) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
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

        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, owner.id.get, orgSettings.set(Feature.EditOrganization -> Feature.EditOrganizationSetting.MEMBERS))

        organizationCommander.modifyOrganization(memberModifyRequest) must beRight
      }
    }
  }

  "move org library permission" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManagementCommander = inject[PlanManagementCommander]
        val (org, owner, admin, member, _) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
          (plan, account)
        }

        val (ownerLibrary, adminLibrary, memberLibrary) = db.readWrite { implicit session =>
          (LibraryFactory.library().withOwner(owner).withOrganization(org).saved,
            LibraryFactory.library().withOwner(admin).withOrganization(org).saved,
            LibraryFactory.library().withOwner(member).withOrganization(org).saved)
        }

        val libraryCommander = inject[LibraryCommander]

        val ownerModifyRequest = LibraryModifications(space = Some(UserSpace(owner.id.get)))
        val adminModifyRequest = LibraryModifications(space = Some(UserSpace(admin.id.get)))
        val memberModifyRequest = LibraryModifications(space = Some(UserSpace(member.id.get)))

        libraryCommander.modifyLibrary(ownerLibrary.id.get, owner.id.get, ownerModifyRequest) must beLeft
        libraryCommander.modifyLibrary(adminLibrary.id.get, admin.id.get, adminModifyRequest) must beLeft
        libraryCommander.modifyLibrary(memberLibrary.id.get, member.id.get, memberModifyRequest) must beLeft

        val orgSettings1 = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings1.set(Feature.RemoveLibraries -> Feature.RemoveLibrariesSetting.ADMINS))

        libraryCommander.modifyLibrary(ownerLibrary.id.get, owner.id.get, ownerModifyRequest) must beRight
        libraryCommander.modifyLibrary(adminLibrary.id.get, admin.id.get, adminModifyRequest) must beRight
        libraryCommander.modifyLibrary(memberLibrary.id.get, member.id.get, memberModifyRequest) must beLeft

        val orgSettings2 = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings2.set(Feature.RemoveLibraries -> Feature.RemoveLibrariesSetting.MEMBERS))

        libraryCommander.modifyLibrary(memberLibrary.id.get, member.id.get, memberModifyRequest) must beRight
      }
    }
  }

  "create slack integration permission" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManagementCommander = inject[PlanManagementCommander]
        val (org, owner, admin, member, _) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
          (plan, account)
        }

        val library = db.readWrite { implicit session => LibraryFactory.library().withOwner(owner).withOrganization(org).saved }

        val libraryCommander = inject[LibraryCommander]

        val ownerModifyRequest = LibraryModifications(subscriptions = Some(Seq(LibrarySubscriptionKey("#general", SlackInfo("https://hooks.slack.com/services/kk/kk")))))

        val adminModifyRequest = LibraryModifications(subscriptions = Some(Seq(
          LibrarySubscriptionKey("#general", SlackInfo("https://hooks.slack.com/services/kk/kk")),
          LibrarySubscriptionKey("#eng", SlackInfo("https://hooks.slack.com/services/ok/ok")))))

        val memberModifyRequest = LibraryModifications(subscriptions = Some(Seq(
          LibrarySubscriptionKey("#general", SlackInfo("https://hooks.slack.com/services/kk/kk")),
          LibrarySubscriptionKey("#eng", SlackInfo("https://hooks.slack.com/services/ok/ok")),
          LibrarySubscriptionKey("#product", SlackInfo("https://hooks.slack.com/services/ko/ko")))))

        libraryCommander.modifyLibrary(library.id.get, owner.id.get, ownerModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, admin.id.get, adminModifyRequest) must beRight
        libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest) must beLeft

        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings.set(Feature.CreateSlackIntegration -> Feature.CreateSlackIntegrationSetting.MEMBERS))

        libraryCommander.modifyLibrary(library.id.get, member.id.get, memberModifyRequest) must beRight
      }
    }
  }

  "export keeps permission" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManagementCommander = inject[PlanManagementCommander]
        val (org, owner, admin, member, _) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
          (plan, account)
        }

        val keepExportCommander = inject[KeepExportCommander]

        val ownerExportRequest = OrganizationKeepExportRequest(owner.id.get, Set(org.id.get))
        val adminExportRequest = OrganizationKeepExportRequest(admin.id.get, Set(org.id.get))
        val memberExportRequest = OrganizationKeepExportRequest(member.id.get, Set(org.id.get))

        Await.result(keepExportCommander.exportKeeps(ownerExportRequest), Duration(3, SECONDS)) must beSuccessfulTry
        Await.result(keepExportCommander.exportKeeps(adminExportRequest), Duration(3, SECONDS)) must beSuccessfulTry
        Await.result(keepExportCommander.exportKeeps(memberExportRequest), Duration(3, SECONDS)) must beFailedTry

        val orgSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings.set(Feature.ExportKeeps -> Feature.ExportKeepsSetting.MEMBERS))

        Await.result(keepExportCommander.exportKeeps(memberExportRequest), Duration(3, SECONDS)) must beSuccessfulTry
      }
    }
  }

  "group messaging permission" should {
    "be configurable" in {
      withDb(modules: _*) { implicit injector =>
        val planManagementCommander = inject[PlanManagementCommander]
        val (org, owner, admin, member, nonMember) = setup()

        val (plan, account) = db.readWrite { implicit session =>
          val plan = PaidPlanFactory.paidPlan().saved
          val account = PaidAccountFactory.paidAccount().withOrganizationId(org.id.get).withPlan(plan.id.get).saved
          (plan, account)
        }

        val mobileRoute = com.keepit.controllers.mobile.routes.MobileContactsController.searchForAllContacts(query = None, limit = None).url
        val extRoute = com.keepit.controllers.ext.routes.ExtUserController.searchForContacts(query = None, limit = None).url

        val mobileContactsController = inject[MobileContactsController]
        val extUserController = inject[ExtUserController]

        // nobody can send group messages
        inject[FakeUserActionsHelper].setUser(owner)
        val ownerMobileRequest1 = FakeRequest("GET", mobileRoute)
        val ownerMobileResult1 = mobileContactsController.searchForAllContacts(query = None, limit = None)(ownerMobileRequest1)
        contentAsJson(ownerMobileResult1).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] != Organization.publicId(org.id.get))
        } must beTrue

        inject[FakeUserActionsHelper].setUser(admin)
        val adminMobileRequest1 = FakeRequest("GET", mobileRoute)
        val adminMobileResult1 = mobileContactsController.searchForAllContacts(query = None, limit = None)(adminMobileRequest1)
        contentAsJson(adminMobileResult1).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] != Organization.publicId(org.id.get))
        } must beTrue

        inject[FakeUserActionsHelper].setUser(member)
        val memberMobileRequest1 = FakeRequest("GET", mobileRoute)
        val memberMobileResult1 = mobileContactsController.searchForAllContacts(query = None, limit = None)(memberMobileRequest1)
        contentAsJson(memberMobileResult1).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] != Organization.publicId(org.id.get))
        } must beTrue

        inject[FakeUserActionsHelper].setUser(owner)
        val ownerExtRequest1 = FakeRequest("GET", mobileRoute)
        val ownerExtResult1 = extUserController.searchForContacts(query = None, limit = None)(ownerExtRequest1)
        contentAsJson(ownerExtResult1).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] != Organization.publicId(org.id.get))
        } must beTrue

        inject[FakeUserActionsHelper].setUser(admin)
        val adminExtRequest1 = FakeRequest("GET", mobileRoute)
        val adminExtResult1 = extUserController.searchForContacts(query = None, limit = None)(adminExtRequest1)
        contentAsJson(adminExtResult1).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] != Organization.publicId(org.id.get))
        } must beTrue

        inject[FakeUserActionsHelper].setUser(member)
        val memberExtRequest1 = FakeRequest("GET", mobileRoute)
        val memberExtResult1 = extUserController.searchForContacts(query = None, limit = None)(memberExtRequest1)
        contentAsJson(memberExtResult1).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] != Organization.publicId(org.id.get))
        } must beTrue

        // allow admins to send group messages
        val orgSettings1 = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings1.set(Feature.MessageOrganization -> Feature.MessageOrganizationSetting.ADMINS))

        inject[FakeUserActionsHelper].setUser(owner)
        val ownerMobileRequest2 = FakeRequest("GET", mobileRoute)
        val ownerMobileResult2 = mobileContactsController.searchForAllContacts(query = None, limit = None)(ownerMobileRequest2)
        contentAsJson(ownerMobileResult2).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(admin)
        val adminMobileRequest2 = FakeRequest("GET", mobileRoute)
        val adminMobileResult2 = mobileContactsController.searchForAllContacts(query = None, limit = None)(adminMobileRequest2)
        contentAsJson(adminMobileResult2).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(member)
        val memberMobileRequest2 = FakeRequest("GET", mobileRoute)
        val memberMobileResult2 = mobileContactsController.searchForAllContacts(query = None, limit = None)(memberMobileRequest2)
        contentAsJson(memberMobileResult2).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get))
        } must beTrue

        inject[FakeUserActionsHelper].setUser(owner)
        val ownerExtRequest2 = FakeRequest("GET", mobileRoute)
        val ownerExtResult2 = extUserController.searchForContacts(query = None, limit = None)(ownerExtRequest2)
        contentAsJson(ownerExtResult2).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(admin)
        val adminExtRequest2 = FakeRequest("GET", mobileRoute)
        val adminExtResult2 = extUserController.searchForContacts(query = None, limit = None)(adminExtRequest2)
        contentAsJson(adminExtResult2).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(member)
        val memberExtRequest2 = FakeRequest("GET", mobileRoute)
        val memberExtResult2 = extUserController.searchForContacts(query = None, limit = None)(memberExtRequest2)
        contentAsJson(memberExtResult2).as[Seq[JsObject]].forall { obj =>
          !((obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get))
        } must beTrue

        // members can send group messages
        val orgSettings2 = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings }
        planManagementCommander.setAccountFeatureSettings(org.id.get, admin.id.get, orgSettings2.set(Feature.MessageOrganization -> Feature.MessageOrganizationSetting.MEMBERS))

        inject[FakeUserActionsHelper].setUser(owner)
        val ownerMobileRequest3 = FakeRequest("GET", mobileRoute)
        val ownerMobileResult3 = mobileContactsController.searchForAllContacts(query = None, limit = None)(ownerMobileRequest3)
        contentAsJson(ownerMobileResult3).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(admin)
        val adminMobileRequest3 = FakeRequest("GET", mobileRoute)
        val adminMobileResult3 = mobileContactsController.searchForAllContacts(query = None, limit = None)(adminMobileRequest3)
        contentAsJson(adminMobileResult3).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(member)
        val memberMobileRequest3 = FakeRequest("GET", mobileRoute)
        val memberMobileResult3 = mobileContactsController.searchForAllContacts(query = None, limit = None)(memberMobileRequest3)
        contentAsJson(memberMobileResult3).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(owner)
        val ownerExtRequest3 = FakeRequest("GET", mobileRoute)
        val ownerExtResult3 = extUserController.searchForContacts(query = None, limit = None)(ownerExtRequest3)
        contentAsJson(ownerExtResult3).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(admin)
        val adminExtRequest3 = FakeRequest("GET", mobileRoute)
        val adminExtResult3 = extUserController.searchForContacts(query = None, limit = None)(adminExtRequest3)
        contentAsJson(adminExtResult3).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue

        inject[FakeUserActionsHelper].setUser(member)
        val memberExtRequest3 = FakeRequest("GET", mobileRoute)
        val memberExtResult3 = extUserController.searchForContacts(query = None, limit = None)(memberExtRequest3)
        contentAsJson(memberExtResult3).as[Seq[JsObject]].exists { obj =>
          (obj \ "kind").as[String] == "org" && (obj \ "id").as[PublicId[Organization]] == Organization.publicId(org.id.get)
        } must beTrue
      }
    }
  }
}
