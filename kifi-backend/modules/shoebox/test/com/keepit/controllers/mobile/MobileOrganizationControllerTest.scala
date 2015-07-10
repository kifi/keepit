package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, JsObject, Json }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobileOrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "MobileOrganizationController" should {
    "serve up organization views" in {
      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, org) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            (user, org)
          }

          val publicId = PublicId[Organization]("2267")

          inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ORGANIZATION))
          val request = route.getOrganization(publicId)
          val response = controller.getOrganization(publicId)(request)

          response === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
      "give an organization view to a user that has view permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, org) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val handle = OrganizationHandle("kifi")
            val org = OrganizationFactory.organization().withHandle(handle).withOwner(user).withName("Forty Two Kifis").saved
            LibraryFactory.libraries(10).map(_.published().withOrganization(org.id)).saved
            LibraryFactory.libraries(15).map(_.withVisibility(LibraryVisibility.ORGANIZATION).withOrganization(org.id)).saved
            LibraryFactory.libraries(20).map(_.secret().withOrganization(org.id)).saved
            (user, org)
          }

          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ORGANIZATION))
          val request = route.getOrganization(publicId)
          val response = controller.getOrganization(publicId)(request)
          status(response) === OK

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "organization" \ "name").as[String] === "Forty Two Kifis"
          (jsonResponse \ "organization" \ "handle").as[String] === "kifi"
          (jsonResponse \ "organization" \ "numLibraries").as[Int] === 10
        }
      }
    }
    "serve up organization cards" in {
      "give a user a list of organizations they belong to" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            for (i <- 1 to 10) {
              val org = OrganizationFactory.organization().withOwner(user).withName("Justice League").saved
              LibraryFactory.libraries(i).map(_.published().withOrganization(org.id)).saved
            }
            user
          }

          inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ORGANIZATION))
          val request = route.getOrganizationsForUser(user.externalId)
          val response = controller.getOrganizationsForUser(user.externalId)(request)
          status(response) === OK

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "organizations") must haveClass[JsArray]
          val cards = (jsonResponse \ "organizations").as[Seq[JsObject]]
          cards.foreach { card => (card \ "name").as[String] === "Justice League" }
          cards.map { card => (card \ "numLibraries").as[Int] }.toSet === (1 to 10).toSet
        }
      }
    }
    def setup(numMembers: Int = 0, numInvitedUsers: Int = 0)(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val members = UserFactory.users(numMembers).saved
        val invitedUsers = UserFactory.users(numInvitedUsers).saved
        val owner = UserFactory.user().withName("A", "Moneybags").saved

        val org = OrganizationFactory.organization()
          .withName("Moneybags, LLC")
          .withOwner(owner)
          .withMembers(members)
          .withInvitedUsers(invitedUsers)
          .saved

        (org, owner, members, invitedUsers)
      }
    }
    "create an organization" in {
      "reject malformed input" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ORGANIZATION))
          val request = route.createOrganization().withBody(Json.parse("""{"asdf": "qwer"}"""))
          val result = controller.createOrganization(request)
          status(result) === BAD_REQUEST
        }
      }
      "reject empty names" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ORGANIZATION))
          val request = route.createOrganization().withBody(Json.parse("""{"name": ""}"""))
          val result = controller.createOrganization(request)
          status(result) === BAD_REQUEST
        }
      }
      "let a user create an organization" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          val orgName = "Banana Capital, USA"
          val orgDescription = "Fun for the whole family"
          val createRequestJson = Json.parse(s"""{"name": "$orgName", "description": "$orgDescription"}""")

          inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ORGANIZATION))
          val request = route.createOrganization().withBody(createRequestJson)
          val result = controller.createOrganization(request)
          status(result) === OK

          val createResponseJson = Json.parse(contentAsString(result))
          (createResponseJson \ "organization" \ "name").as[String] === orgName
          (createResponseJson \ "organization" \ "description").as[Option[String]] === Some(orgDescription)
        }
      }
    }

    "when modifyOrganization is called:" in {
      def setupModify(implicit injector: Injector) = db.readWrite { implicit session =>
        val owner = UserFactory.user().withName("Captain", "America").saved
        val org = OrganizationFactory.organization().withOwner(owner).withName("Worldwide Consortium of Earth").withHandle(OrganizationHandle("Earth")).saved
        (org, owner)
      }

      "succeed for valid name" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setupModify
          inject[FakeUserActionsHelper].setUser(owner, Set(ExperimentType.ORGANIZATION))
          val publicId = Organization.publicId(org.id.get)

          val json = """{ "name": "bob" }"""
          val request = route.modifyOrganization(publicId).withBody(Json.parse(json))
          val response = controller.modifyOrganization(publicId)(request)
          status(response) === OK
        }
      }

      "succeed for valid modifications" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setupModify
          inject[FakeUserActionsHelper].setUser(owner, Set(ExperimentType.ORGANIZATION))
          val publicId = Organization.publicId(org.id.get)

          val json = """ {"none":["view_organization"],"owner":["invite_members","edit_organization","view_organization","remove_libraries","modify_members","remove_members","add_libraries"],"member":["view_organization","add_libraries"]} """
          val request = route.modifyOrganization(publicId).withBody(Json.parse(json))
          val response = controller.modifyOrganization(publicId)(request)
          status(response) === OK
        }
      }

      "fail on invalid modifications" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setupModify
          inject[FakeUserActionsHelper].setUser(owner, Set(ExperimentType.ORGANIZATION))
          val publicId = Organization.publicId(org.id.get)

          val json = """{ "basePermissions": {"member":[]} }""" // all members must at least be able to view the organization
          val request = route.modifyOrganization(publicId).withBody(Json.parse(json))
          val response = controller.modifyOrganization(publicId)(request)
          response === OrganizationFail.BAD_PARAMETERS
        }
      }

      "fail for missing role in basePermissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setupModify
          inject[FakeUserActionsHelper].setUser(owner, Set(ExperimentType.ORGANIZATION))
          val publicId = Organization.publicId(org.id.get)

          val json = """{ "basePermissions": {"owner": [], "none": []} }"""
          val request = route.modifyOrganization(publicId).withBody(Json.parse(json))
          val response = controller.modifyOrganization(publicId)(request)
          response === OrganizationFail.BAD_PARAMETERS
        }
      }
    }

    "when deleteOrganization is called:" in {
      def setupDelete(implicit injector: Injector) = db.readWrite { implicit session =>
        val owner = UserFactory.user().withName("Dr", "Papaya").saved
        val member = UserFactory.user().withName("Hansel", "Schmidt").saved
        val org = OrganizationFactory.organization().withOwner(owner).withName("Papaya Republic of California").withHandle(OrganizationHandle("papaya_republic")).saved
        inject[OrganizationMembershipRepo].save(org.newMembership(member.id.get, OrganizationRole.MEMBER))
        (org, owner, member)
      }

      "succeed for owner" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, _) = setupDelete
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner, Set(ExperimentType.ORGANIZATION))
          val request = route.deleteOrganization(publicId)
          val result = controller.deleteOrganization(publicId)(request)
          status(result) === NO_CONTENT
        }
      }

      "fail for non-owners" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, _, member) = setupDelete
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(member, Set(ExperimentType.ORGANIZATION))
          val request = route.deleteOrganization(publicId)
          val result = controller.deleteOrganization(publicId)(request)

          result === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }
    }
  }

  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[MobileOrganizationController]
  private def route = com.keepit.controllers.mobile.routes.MobileOrganizationController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
      contentType(result) must beSome("application/json")
    }
  }
}
