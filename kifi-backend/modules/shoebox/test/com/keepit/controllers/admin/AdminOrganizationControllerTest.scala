package com.keepit.controllers.admin

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.payments.PaidPlan
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.mvc.{ Result, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration
import scala.util.Try

class AdminOrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  private def controller(implicit injector: Injector) = inject[AdminOrganizationController]
  private def route = com.keepit.controllers.admin.routes.AdminOrganizationController
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "AdminOrganizationController" should {
    "propagate default plan settings to existing org configs" in {
      def chunkedResultToJsArray(r: Result): Future[Try[JsArray]] = {
        r.body.run(Iteratee.getChunks).map { chunks =>
          Try {
            val payloads = chunks.init.map { c =>
              // This is my hella-ghetto way of dropping the obnoxious chunked HTTP headers
              // Chunks things come in like this:
              //     XYZ
              //     <actual stuff>
              //     XYZ
              //     <actual stuff>
              // The XYZ is some sort of indicator about the size of the chunk coming down
              JsString(new String(c).dropWhile(!_.isWhitespace))
            }
            JsArray(payloads)
          }
        }
      }
      def chunkedResultAsJson(result: Future[Result]): JsArray = {
        Await.result(result.flatMap(chunkedResultToJsArray), Duration.Inf).get
      }
      "work" in {
        withDb(modules: _*) { implicit injector =>
          val (plan, orgs, admin) = db.readWrite { implicit session =>
            val admin = UserFactory.user().saved
            val plan = PaidPlanFactory.paidPlan().saved
            val orgs = OrganizationFactory.organizations(10).map(_.withOwner(admin)).saved

            // Now break the org configs so we can see if this endpoint works
            orgs.foreach { org =>
              orgConfigRepo.save(orgConfigRepo.getByOrgId(org.id.get).withSettings(OrganizationSettings.empty))
            }
            (plan, orgs, admin)
          }

          // start with a bunch of orgs with incomplete settings
          // hit the endpoint to bring them up-to-speed with the plan that they're on

          inject[FakeUserActionsHelper].setUser(admin, Set(UserExperimentType.ADMIN))
          val payload = Json.obj("confirmation" -> "really do it")
          val request = route.applyDefaultSettingsToOrgConfigs().withBody(payload)
          val result = controller.applyDefaultSettingsToOrgConfigs()(request)
          status(result) === OK

          val responsePayload = chunkedResultAsJson(result)

          // check to make sure they're reached parity with the plan

          db.readOnlyMaster { implicit session =>
            orgs.foreach { org =>
              orgConfigRepo.getByOrgId(org.id.get).settings === plan.defaultSettings
            }
          }
          1 === 1
        }
      }
      "work for both adding and removing features" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            (org, owner)
          }
          val newPlan1 = db.readWrite { implicit session =>
            val oldPlan = paidPlanRepo.get(Id[PaidPlan](1))
            val newFeatureSet = oldPlan.defaultSettings.selections.keySet -- Set(StaticFeature.CreateSlackIntegration, StaticFeature.EditOrganization)
            val newFeatureSettings = newFeatureSet.map { feature => feature -> oldPlan.defaultSettings.selections(feature) }.toMap
            paidPlanRepo.save(oldPlan.copy(editableFeatures = newFeatureSet, defaultSettings = OrganizationSettings(newFeatureSettings))) // mock db migration, remove features
          }

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ADMIN))
          val payload = Json.obj("confirmation" -> "really do it")
          val request = route.applyDefaultSettingsToOrgConfigs().withBody(payload)
          val response = controller.applyDefaultSettingsToOrgConfigs()(request)

          status(response) === OK
          val responsePayload = chunkedResultAsJson(response)

          val newConfig1 = db.readOnlyMaster(implicit s => orgConfigRepo.getByOrgId(org.id.get))
          newConfig1.settings === newPlan1.defaultSettings

          val newPlan2 = db.readWrite { implicit session =>
            val oldPlan = paidPlanRepo.get(Id[PaidPlan](1))
            val newFeatureSet = PaidPlanFactory.testPlanEditableFeatures
            val newFeatureSettings = PaidPlanFactory.testPlanSettings
            paidPlanRepo.save(oldPlan.copy(editableFeatures = newFeatureSet, defaultSettings = newFeatureSettings)) // mock db migration, add features
          }

          val response2 = controller.applyDefaultSettingsToOrgConfigs()(request)

          val responsePayload2 = chunkedResultAsJson(response2)

          status(response2) === OK
          val newConfig2 = db.readOnlyMaster(implicit s => orgConfigRepo.getByOrgId(org.id.get))
          newConfig2.settings === newPlan2.defaultSettings

          newConfig2.settings.selections.keySet.diff(newConfig1.settings.selections.keySet) === Set(StaticFeature.CreateSlackIntegration, StaticFeature.EditOrganization)
        }
      }

      "reset deprecated settings" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            (org, owner)
          }

          val oldConfig = db.readOnlyMaster(implicit s => orgConfigRepo.getByOrgId(org.id.get))

          db.readWrite { implicit session =>
            val oldPlan = paidPlanRepo.get(Id[PaidPlan](1))
            paidPlanRepo.save(oldPlan.copy(defaultSettings = oldPlan.defaultSettings.withFeatureSetTo((StaticFeature.ViewMembers, StaticFeatureSetting.DISABLED)))) // mock db migration, remove features
          }

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ADMIN))
          val payload = Json.obj("confirmation" -> "really do it", "deprecatedSettings" -> Json.obj("view_members" -> "anyone")) // see PaidPlanFactory.testPlanSettings, should be equal to one of the kv pairs
          val request = route.applyDefaultSettingsToOrgConfigs().withBody(payload)
          val response = controller.applyDefaultSettingsToOrgConfigs()(request)

          status(response) === OK
          val responsePayload = chunkedResultAsJson(response)

          val newConfig = db.readOnlyMaster(implicit s => orgConfigRepo.getByOrgId(org.id.get))
          newConfig.settings must equalTo(oldConfig.settings.withFeatureSetTo((StaticFeature.ViewMembers, StaticFeatureSetting.DISABLED)))
        }
      }
    }
  }
}
