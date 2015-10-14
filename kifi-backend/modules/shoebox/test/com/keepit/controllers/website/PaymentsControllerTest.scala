package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.controllers.admin.AdminPaymentsController
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.payments.{ BillingCycle, PaidPlanInfo, PaidPlanRepo, DollarAmount, PlanManagementCommander, PaidPlan, FakeStripeClientModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.JsObject
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._

class PaymentsControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[PaymentsController]
  private def route = com.keepit.controllers.website.routes.PaymentsController
  private def adminRoute = com.keepit.controllers.admin.routes.AdminPaymentsController

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeStripeClientModule()
  )

  "PaymentsController" should {

    def setup()(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val owner = UserFactory.user().saved
        val org = OrganizationFactory.organization().withOwner(owner).saved
        (org, owner)
      }
    }

    "get an account's state" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val planCommander = inject[PlanManagementCommander]
        val (org, owner) = setup()

        val publicId = Organization.publicId(org.id.get)
        inject[FakeUserActionsHelper].setUser(owner)
        val request = route.getAccountState(publicId)
        val response = controller.getAccountState(publicId)(request)
        val payload = contentAsJson(response).as[JsObject]

        (payload \ "users").as[Int] must beEqualTo(1)
        (payload \ "credit").as[DollarAmount] must beEqualTo(DollarAmount(-4677))

        val planJson = (payload \ "plan").as[JsObject]
        val actualPlan = planCommander.currentPlan(org.id.get)
        (planJson \ "id").as[PublicId[PaidPlan]] must beEqualTo(PaidPlan.publicId(actualPlan.id.get))
        (planJson \ "name").as[String] must beEqualTo("Free")
        (planJson \ "pricePerUser").as[DollarAmount] must beEqualTo(DollarAmount(10000))
        (planJson \ "cycle").as[Int] must beEqualTo(1)
        (planJson \ "features").as[Set[Feature]] must beEqualTo(PaidPlanFactory.testPlanEditableFeatures)
      }
    }

    "get an organization's configuration" in {
      "use the correct format" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setup()
          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAccountFeatureSettings(publicId)
          val response = controller.getAccountFeatureSettings(publicId)(request)
          val payload = contentAsJson(response).as[JsObject]
          (payload \ "name").as[String] === "test"
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "setting").as[String] === "members"
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "editable").as[Boolean] === true
        }
      }
    }

    "apply new default settings to org configurations" in {
      "apply migration properly" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setup()
          val newPlan1 = db.readWrite { implicit session =>
            val oldPlan = paidPlanRepo.get(Id[PaidPlan](1))
            val newFeatureSet = oldPlan.defaultSettings.kvs.keySet -- Set(Feature.CreateSlackIntegration, Feature.EditOrganization)
            val newFeatureSettings = newFeatureSet.map { feature => feature -> oldPlan.defaultSettings.kvs(feature) }.toMap
            paidPlanRepo.save(oldPlan.copy(editableFeatures = newFeatureSet, defaultSettings = OrganizationSettings(newFeatureSettings))) // mock db migration, remove features
          }

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ADMIN))
          val request = adminRoute.applyDefaultSettingsToOrgConfigs()
          val response = inject[AdminPaymentsController].applyDefaultSettingsToOrgConfigs()(request)

          status(response) must equalTo(200)
          val newConfig1 = db.readOnlyMaster(implicit s => orgConfigRepo.getByOrgId(org.id.get))
          newConfig1.settings === newPlan1.defaultSettings

          val newPlan2 = db.readWrite { implicit session =>
            val oldPlan = paidPlanRepo.get(Id[PaidPlan](1))
            val newFeatureSet = PaidPlanFactory.testPlanEditableFeatures
            val newFeatureSettings = PaidPlanFactory.testPlanSettings
            paidPlanRepo.save(oldPlan.copy(editableFeatures = newFeatureSet, defaultSettings = newFeatureSettings)) // mock db migration, add features
          }

          val response2 = inject[AdminPaymentsController].applyDefaultSettingsToOrgConfigs()(request)

          status(response2) must equalTo(200)
          val newConfig2 = db.readOnlyMaster(implicit s => orgConfigRepo.getByOrgId(org.id.get))
          newConfig2.settings === newPlan2.defaultSettings

          newConfig2.settings.kvs.keySet.diff(newConfig1.settings.kvs.keySet) === Set(Feature.CreateSlackIntegration, Feature.EditOrganization)
        }
      }
    }

    "get active plans" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (org, owner) = setup()
        val publicId = Organization.publicId(org.id.get)
        val currentPlan = inject[PlanManagementCommander].currentPlan(org.id.get)

        val standardPlans = db.readWrite { implicit s =>
          val planRepo = inject[PaidPlanRepo]
          val standardAnnualPlan = planRepo.save(
            PaidPlan(kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("standard_annual"), displayName = "Standard",
              billingCycle = BillingCycle(12), pricePerCyclePerUser = DollarAmount(8004),
              editableFeatures = PaidPlanFactory.testPlanEditableFeatures, defaultSettings = PaidPlanFactory.testPlanSettings)
          )
          val standardBiannualPlan = planRepo.save(
            PaidPlan(kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("standard_annual"), displayName = "Standard",
              billingCycle = BillingCycle(6), pricePerCyclePerUser = DollarAmount(8004),
              editableFeatures = PaidPlanFactory.testPlanEditableFeatures, defaultSettings = PaidPlanFactory.testPlanSettings)
          )
          val standardMonthlyPlan = planRepo.save(
            PaidPlan(kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("standard_monthly"), displayName = "Standard",
              billingCycle = BillingCycle(1), pricePerCyclePerUser = DollarAmount(800),
              editableFeatures = PaidPlanFactory.testPlanEditableFeatures, defaultSettings = PaidPlanFactory.testPlanSettings)
          )
          Seq(standardMonthlyPlan, standardBiannualPlan, standardAnnualPlan)
        }

        inject[FakeUserActionsHelper].setUser(owner)
        val request = route.getAvailablePlans(publicId)
        val response = controller.getAvailablePlans(publicId)(request)

        val plansByName = contentAsJson(response)
        (plansByName \ "plans" \ "Free").as[Seq[PaidPlanInfo]] === Seq(currentPlan.asInfo)
        (plansByName \ "plans" \ "Standard").as[Seq[PaidPlanInfo]] === standardPlans.map(_.asInfo).sortBy(_.cycle.month)
        (plansByName \ "current").as[PublicId[PaidPlan]] === PaidPlan.publicId(currentPlan.id.get)
      }
    }
  }
}
