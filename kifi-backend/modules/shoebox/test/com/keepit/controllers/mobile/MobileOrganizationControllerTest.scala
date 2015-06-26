package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.common.core._

import scala.concurrent.Future

class MobileOrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "MobileOrganizationController" should {
    "when getOrganization is called:" in {
      "succeed with user that has view permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, org) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val handle = PrimaryOrganizationHandle(original = OrganizationHandle("Kifi"), normalized = OrganizationHandle("kifi"))
            val org = OrganizationFactory.organization().withHandle(handle).withOwner(user).withName("Forty Two Kifis").saved
            LibraryFactory.libraries(10).map(_.published().withOrganization(org.id)).saved
            LibraryFactory.libraries(15).map(_.withVisibility(LibraryVisibility.ORGANIZATION).withOrganization(org.id)).saved
            LibraryFactory.libraries(20).map(_.secret().withOrganization(org.id)).saved
            (user, org)
          }

          implicit val config = inject[PublicIdConfiguration]
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(user)
          val request = route.getOrganization(publicId)
          val response = controller.getOrganization(publicId)(request)
          status(response) === OK

          val jsonResponse = Json.parse(contentAsString(response)) tap println
          (jsonResponse \ "name").as[String] === "Forty Two Kifis"
          (jsonResponse \ "handle").as[String] === "Kifi"
          (jsonResponse \ "publicLibraries").as[Int] === 10
          (jsonResponse \ "organizationLibraries").as[Int] === 15
          (jsonResponse \ "secretLibraries").as[Int] === 20
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, org) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            (user, org)
          }

          implicit val config = inject[PublicIdConfiguration]
          val publicId = PublicId[Organization]("2267")

          inject[FakeUserActionsHelper].setUser(user)
          val request = route.getOrganization(publicId)
          val response = controller.getOrganization(publicId)(request)

          response === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
    }
  }

  private def controller(implicit injector: Injector) = inject[MobileOrganizationController]
  private def route = com.keepit.controllers.mobile.routes.MobileOrganizationController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      contentType(result) must beSome("application/json")
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
    }
  }
}
