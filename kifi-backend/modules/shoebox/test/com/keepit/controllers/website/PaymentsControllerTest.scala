package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import org.specs2.matcher._
import com.keepit.controllers.admin.AdminPaymentsController
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model._
import com.keepit.payments._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.matcher.{ Expectable, Matcher, Delta }
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsValue, Json, JsObject }
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.common.time._

class PaymentsControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[PaymentsController]
  private def route = com.keepit.controllers.website.routes.PaymentsController

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
        val member = UserFactory.user().saved
        val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
        val account = inject[PaidAccountRepo].getByOrgId(org.id.get)
        val plan = inject[PaidPlanRepo].get(account.planId)
        (org, owner, account, plan)
      }
    }

    def setupNormalPlans()(implicit injector: Injector) = {
      db.readWrite { implicit s =>
        val planRepo = inject[PaidPlanRepo]
        val standardAnnualPlan = planRepo.save(
          PaidPlan(kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("standard_annual"), displayName = "Standard",
            billingCycle = BillingCycle(12), pricePerCyclePerUser = DollarAmount(8004),
            editableFeatures = PaidPlanFactory.testPlanEditableFeatures, defaultSettings = PaidPlanFactory.testPlanSettings)
        )
        val standardBiannualPlan = planRepo.save(
          PaidPlan(kind = PaidPlan.Kind.NORMAL, name = Name[PaidPlan]("standard_biannual"), displayName = "Standard",
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
    }

    "get an organization's configuration" in {
      "use the correct format" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, _, _) = setup()
          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAccountFeatureSettings(publicId)
          val response = controller.getAccountFeatureSettings(publicId)(request)
          val payload = contentAsJson(response).as[JsObject]
          (payload \ "showUpsells").as[Boolean] === true
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "setting").as[String] === "members"
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "editable").as[Boolean] === true
        }
      }
    }

    "get paid plans" in {
      "get active normal plans" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, _, _) = setup()
          val publicId = Organization.publicId(org.id.get)
          val currentPlan = planManagementCommander.currentPlan(org.id.get)
          val standardPlans = setupNormalPlans()

          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAvailablePlans(publicId)
          val response = controller.getAvailablePlans(publicId)(request)

          val plansByName = contentAsJson(response)
          (plansByName \ "plans" \ "Free").as[Seq[PaidPlanInfo]] === Seq(currentPlan.asInfo)
          (plansByName \ "plans" \ "Standard").as[Seq[PaidPlanInfo]] === standardPlans.map(_.asInfo).sortBy(_.cycle.month)
          (plansByName \ "current").as[PublicId[PaidPlan]] === PaidPlan.publicId(currentPlan.id.get)
        }
      }

      "get custom plans when org is on one" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, _, _) = setup()
          val publicId = Organization.publicId(org.id.get)
          val currentPlan = planManagementCommander.currentPlan(org.id.get)
          val standardPlans = setupNormalPlans()

          val customPlans = db.readWrite { implicit session =>
            val enterpriseAnnual = PaidPlanFactory.paidPlan().withKind(PaidPlan.Kind.CUSTOM)
              .withDisplayName("Enterprise").withBillingCycle(BillingCycle(12)).withPricePerCyclePerUser(80040).saved
            val enterpriseBiannual = PaidPlanFactory.paidPlan().withKind(PaidPlan.Kind.CUSTOM)
              .withDisplayName("Enterprise").withBillingCycle(BillingCycle(6)).withPricePerCyclePerUser(40020).saved
            val enterpriseMonthly = PaidPlanFactory.paidPlan().withKind(PaidPlan.Kind.CUSTOM)
              .withDisplayName("Enterprise").withBillingCycle(BillingCycle(1)).withPricePerCyclePerUser(8000).saved
            Seq(enterpriseAnnual, enterpriseBiannual, enterpriseMonthly)
          }

          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAvailablePlans(publicId)
          val response = controller.getAvailablePlans(publicId)(request)

          // don't get any custom plans
          val plansByName = contentAsJson(response)
          (plansByName \ "plans" \ "Free").as[Seq[PaidPlanInfo]] === Seq(currentPlan.asInfo)
          (plansByName \ "plans" \ "Standard").as[Seq[PaidPlanInfo]] === standardPlans.map(_.asInfo).sortBy(_.cycle.month)
          (plansByName \ "current").as[PublicId[PaidPlan]] === PaidPlan.publicId(currentPlan.id.get)
          (plansByName \ "plans" \ "Enterprise").asOpt[Seq[PaidPlanInfo]] === None

          planManagementCommander.changePlan(org.id.get, customPlans.head.id.get, ActionAttribution(None, admin = Some(Id[User](1)))) // only admins can put an org on a custom plan

          val request2 = route.getAvailablePlans(publicId)
          val response2 = controller.getAvailablePlans(publicId)(request2)

          // don't get any custom plans
          val plansByNameWithCustom = contentAsJson(response2)
          (plansByNameWithCustom \ "plans" \ "Free").as[Seq[PaidPlanInfo]] === Seq(currentPlan.asInfo)
          (plansByNameWithCustom \ "plans" \ "Standard").as[Seq[PaidPlanInfo]] === standardPlans.map(_.asInfo).sortBy(_.cycle.month)
          (plansByNameWithCustom \ "current").as[PublicId[PaidPlan]] === PaidPlan.publicId(customPlans.head.id.get)
          (plansByNameWithCustom \ "plans" \ "Enterprise").asOpt[Seq[PaidPlanInfo]] === Some(customPlans.map(_.asInfo).sortBy(_.cycle.month))
        }
      }
    }

    "report an account's state" in {
      "have all the expected fields" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val planCommander = inject[PlanManagementCommander]
          val (org, owner, account, plan) = setup()

          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAccountState(publicId)
          val response = controller.getAccountState(publicId)(request)
          val payload = contentAsJson(response).as[JsObject]
          (payload \ "users").as[Int] must beEqualTo(account.activeUsers)
          (payload \ "balance").as[JsObject] === Json.obj("cents" -> account.credit.toCents)
          (payload \ "charge").as[JsObject] === Json.obj("cents" -> (plan.pricePerCyclePerUser * account.activeUsers).toCents)

          val planJson = (payload \ "plan").as[JsObject]
          val actualPlan = planCommander.currentPlan(org.id.get)
          (planJson \ "id").as[PublicId[PaidPlan]] must beEqualTo(PaidPlan.publicId(actualPlan.id.get))
          (planJson \ "name").as[String] must beEqualTo(plan.displayName)
          (planJson \ "pricePerUser").as[JsObject] === Json.obj("cents" -> plan.pricePerCyclePerUser.toCents)
          (planJson \ "cycle").as[Int] must beEqualTo(plan.billingCycle.month)
          (planJson \ "features").as[Set[Feature]] must beEqualTo(PaidPlanFactory.testPlanEditableFeatures)
        }
      }
      "when changing plans" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, curPlan, newPlan) = db.readWrite { implicit session =>
            val curPlan = PaidPlanFactory.paidPlan().withBillingCycle(BillingCycle.months(1)).withPricePerCyclePerUser(DollarAmount.ZERO).saved
            val newPlan = PaidPlanFactory.paidPlan().withBillingCycle(BillingCycle.months(1)).withPricePerCyclePerUser(DollarAmount.dollars(42)).saved
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).withPlan(curPlan.id.get.id).saved
            (org, owner, curPlan, newPlan)
          }
          val orgId = Organization.publicId(org.id.get)
          val newPlanId = PaidPlan.publicId(newPlan.id.get)

          inject[FakeUserActionsHelper].setUser(owner)

          val initialBalance = db.readOnlyMaster { implicit s => paidAccountRepo.getByOrgId(org.id.get).credit }
          val response1 = controller.getAccountState(orgId)(route.getAccountState(orgId))
          status(response1) === OK
          val result1 = contentAsJson(response1)
          (result1 \ "plan" \ "pricePerUser").as[DollarAmount](DollarAmount.formatAsCents) === curPlan.pricePerCyclePerUser
          (result1 \ "balance").as[DollarAmount](DollarAmount.formatAsCents) === initialBalance

          val setRequest = route.updatePlan(orgId, newPlanId)
          val setResponse = controller.updatePlan(orgId, newPlanId)(setRequest)
          status(setResponse) === OK
          val finalBalance = db.readOnlyMaster { implicit s => paidAccountRepo.getByOrgId(org.id.get).credit }

          val response2 = controller.getAccountState(orgId)(route.getAccountState(orgId))
          status(response2) === OK
          val result2 = contentAsJson(response2)
          (result2 \ "plan" \ "pricePerUser").as[DollarAmount](DollarAmount.formatAsCents) === newPlan.pricePerCyclePerUser
          (result2 \ "balance").as[DollarAmount](DollarAmount.formatAsCents) === finalBalance
        }
      }

      "when adding/removing members" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando, plan) = db.readWrite { implicit session =>
            val plan = PaidPlanFactory.paidPlan().withBillingCycle(BillingCycle.months(1)).withPricePerCyclePerUser(DollarAmount.dollars(42)).saved
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).withPlan(plan.id.get.id).saved
            paidAccountRepo.save(paidAccountRepo.getByOrgId(org.id.get).withPlanRenewal(currentDateTime plusDays 15)) // set future renewal date to test partial refunds / costs
            (org, owner, rando, plan)
          }
          val orgId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner)

          val initialBalance = db.readOnlyMaster { implicit s => paidAccountRepo.getByOrgId(org.id.get).credit }
          val response1 = controller.getAccountState(orgId)(route.getAccountState(orgId))
          status(response1) === OK
          val result1 = contentAsJson(response1)
          (result1 \ "balance").as[DollarAmount](DollarAmount.formatAsCents) === initialBalance

          orgMembershipCommander.addMembership(OrganizationMembershipAddRequest(org.id.get, owner.id.get, rando.id.get, adminIdOpt = None))

          val addedBalance = db.readOnlyMaster { implicit s => paidAccountRepo.getByOrgId(org.id.get).credit }
          addedBalance must beLessThan(initialBalance) // simple sanity check, actual logic should be tested in the commander

          val response2 = controller.getAccountState(orgId)(route.getAccountState(orgId))
          status(response2) === OK
          val result2 = contentAsJson(response2)
          (result2 \ "balance").as[DollarAmount](DollarAmount.formatAsCents) === addedBalance

          orgMembershipCommander.removeMembership(OrganizationMembershipRemoveRequest(org.id.get, owner.id.get, rando.id.get))

          val removedBalance = db.readOnlyMaster { implicit s => paidAccountRepo.getByOrgId(org.id.get).credit }
          removedBalance === initialBalance

          val response3 = controller.getAccountState(orgId)(route.getAccountState(orgId))
          status(response3) === OK
          val result3 = contentAsJson(response3)
          (result3 \ "balance").as[DollarAmount](DollarAmount.formatAsCents) === removedBalance
        }
      }
    }
    "get and set a credit card token" in {
      "handle permissions correctly" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, orgAdmin, member, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val orgAdmin = UserFactory.user().saved
            val member = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).withAdmins(Seq(orgAdmin)).withMembers(Seq(member)).saved
            (org, owner, orgAdmin, member, rando)
          }
          val publicId = Organization.publicId(org.id.get)

          val goodUsers = Seq(owner, orgAdmin)
          val badUsers = Seq(member, rando)

          for (user <- goodUsers) {
            inject[FakeUserActionsHelper].setUser(user)
            val setPayload = Json.obj("token" -> RandomStringUtils.randomAlphanumeric(10))
            val setRequest = route.setCreditCardToken(publicId).withBody(setPayload)
            val setResponse = controller.setCreditCardToken(publicId)(setRequest)
            status(setResponse) === OK

            val getRequest = route.getCreditCardToken(publicId)
            val getResponse = controller.getCreditCardToken(publicId)(getRequest)
            status(getResponse) === OK
            (contentAsJson(getResponse) \ "token").asOpt[String] must beSome
          }
          for (user <- badUsers) {
            inject[FakeUserActionsHelper].setUser(user)
            val setPayload = Json.obj("token" -> RandomStringUtils.randomAlphanumeric(10))
            val setRequest = route.setCreditCardToken(publicId).withBody(setPayload)
            val setResponse = controller.setCreditCardToken(publicId)(setRequest)
            status(setResponse) === FORBIDDEN

            val getRequest = route.getCreditCardToken(publicId)
            val getResponse = controller.getCreditCardToken(publicId)(getRequest)
            status(getResponse) === FORBIDDEN
          }
          1 === 1
        }
      }
    }

    "serve up the activity log" in {
      "page through events correctly" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            (org, owner, rando)
          }
          val publicId = Organization.publicId(org.id.get)
          // Populate the activity log with a ton of dumb events
          val n = 5
          (1 to n).foreach { _ =>
            orgMembershipCommander.addMembership(OrganizationMembershipAddRequest(org.id.get, owner.id.get, rando.id.get, adminIdOpt = None))
            orgMembershipCommander.removeMembership(OrganizationMembershipRemoveRequest(org.id.get, owner.id.get, rando.id.get))
          }

          val pageSize = 3
          val expectedPages = db.readOnlyMaster { implicit session =>
            inject[AccountEventRepo].count must beGreaterThanOrEqualTo(3 * pageSize)
            val allEvents = inject[AccountEventRepo].all.filter(e => AccountEventKind.activityLog.contains(e.action.eventType))
            val orderedEvents = allEvents.sortBy(ae => (ae.eventTime.getMillis, ae.id.get.id)).reverse
            orderedEvents.map(e => e.id.get).grouped(pageSize).toList
          }

          inject[FakeUserActionsHelper].setUser(owner)
          val actualPages = Iterator.iterate(Seq.empty[Id[AccountEvent]]) { prevPage =>
            val bookendOpt = prevPage.lastOption.map(AccountEvent.publicId(_).id)
            val request = route.getEvents(publicId, pageSize, bookendOpt)
            val response = controller.getEvents(publicId, pageSize, bookendOpt)(request)
            val events = (contentAsJson(response) \ "events").as[Seq[JsValue]]
            events.map { j => AccountEvent.decodePublicId((j \ "id").as[PublicId[AccountEvent]]).get }
          }.toStream.tail.takeWhile(_.nonEmpty).toList

          actualPages.length === expectedPages.length
          (actualPages zip expectedPages) foreach {
            case (actual, expected) => actual === expected
          }
          1 === 1
        }
      }
    }
  }
}
