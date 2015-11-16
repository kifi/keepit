package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.mail.EmailAddress
import com.keepit.controllers.core.AuthController
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ UserEmailAddressRepo, Organization, OrganizationFactory, OrganizationFail, UserFactory }
import com.keepit.test.ShoeboxTestInjector
import com.sun.corba.se.spi.servicecontext.ServiceContextData
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class OrganizationDomainOwnershipControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[OrganizationDomainOwnershipController]
  private def route = com.keepit.controllers.website.routes.OrganizationDomainOwnershipController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
      contentType(result) must beSome("application/json")
    }
  }

  val modules = Seq()

  def setup(implicit injector: Injector) = db.readWrite { implicit session =>
    val owner = UserFactory.user().withEmailAddress("bonobo@primates.org").saved
    val org = OrganizationFactory.organization().withOwner(owner).saved
    val user = UserFactory.user().saved
    (owner, org, user)
  }

  "organization domain ownership commander" should {
    "get/add/remove domains" in {
      withDb(modules: _*) { implicit injector =>
        val (owner, org, _) = setup

        val publicId = Organization.publicId(org.id.get)

        inject[FakeUserActionsHelper].setUser(owner)
        val getRequest1 = route.getDomains(publicId)
        val getResult1 = controller.getDomains(publicId)(getRequest1)
        status(getResult1) must beEqualTo(OK)
        contentAsJson(getResult1).as[Set[String]] must beEmpty

        val badAddRequest = route.addDomain(publicId).withBody(Json.obj("domain" -> "primate"))
        val badAddResult = controller.addDomain(publicId)(badAddRequest)
        status(badAddResult) must beEqualTo(BAD_REQUEST)
        (contentAsJson(badAddResult) \ "error").as[String] must beEqualTo("invalid_domain_name")

        val badAddRequest2 = route.addDomain(publicId).withBody(Json.obj("domain" -> "monkeys.org"))
        val badAddResult2 = controller.addDomain(publicId)(badAddRequest2)
        status(badAddResult2) must beEqualTo(FORBIDDEN)
        (contentAsJson(badAddResult2) \ "error").as[String] must beEqualTo("unverified_email_domain")

        val addRequest = route.addDomain(publicId).withBody(Json.obj("domain" -> "primates.org"))
        val addResult = controller.addDomain(publicId)(addRequest)
        status(addResult) must beEqualTo(OK)
        (contentAsJson(addResult) \ "domain").as[String] must beEqualTo("primates.org")

        val getRequest2 = route.getDomains(publicId)
        val getResult2 = controller.getDomains(publicId)(getRequest2)
        status(getResult2) must beEqualTo(OK)
        contentAsJson(getResult2).as[Set[String]] must beEqualTo(Set("primates.org"))

        val removeRequest = route.removeDomain(publicId).withBody(Json.obj("domain" -> "primates.org"))
        val removeResult = controller.removeDomain(publicId)(removeRequest)
        status(removeResult) must beEqualTo(OK)

        val getRequest3 = route.getDomains(publicId)
        val getResult3 = controller.getDomains(publicId)(getRequest3)
        status(getResult3) must beEqualTo(OK)
        contentAsJson(getResult3).as[Set[String]] must beEmpty
      }
    }

    "add a domain after pending email is verified" in {
      withDb(modules: _*) { implicit injector =>
        val (owner, org, _) = setup
        val publicId = Organization.publicId(org.id.get)

        val emailToAdd = EmailAddress("gorilla@primates.org")

        inject[FakeUserActionsHelper].setUser(owner)
        val addPendingRequest = route.addDomainOwnershipAfterVerification(publicId)
          .withBody(Json.obj("email" -> emailToAdd.address))
        val addPendingResponse = controller.addDomainOwnershipAfterVerification(publicId)(addPendingRequest)
        status(addPendingResponse) must beEqualTo(OK)

        val addEmailRequest = com.keepit.controllers.website.routes.UserController.addEmail().withBody(Json.obj("email" -> emailToAdd.address))
        val addEmailResponse = inject[UserController].addEmail()(addEmailRequest)
        status(addEmailResponse) must beEqualTo(OK)

        val pendingEmail = db.readOnlyMaster(implicit s => inject[UserEmailAddressRepo].getByAddress(EmailAddress("gorilla@primates.org")))
        val verifyRequest = com.keepit.controllers.core.routes.AuthController.verifyEmail(pendingEmail.get.verificationCode.get)
        val verifyResponse = inject[AuthController].verifyEmail(pendingEmail.get.verificationCode.get)(verifyRequest)
        Await.result(verifyResponse, Duration(5, "seconds"))

        val getRequest = route.getDomains(publicId)
        val getResult = controller.getDomains(publicId)(getRequest)
        status(getResult) must beEqualTo(OK)
        contentAsJson(getResult).as[Set[String]] must beEqualTo(Set("primates.org"))
      }
    }
  }
}
