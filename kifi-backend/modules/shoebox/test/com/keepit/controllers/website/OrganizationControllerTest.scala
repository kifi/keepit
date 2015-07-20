package com.keepit.controllers.website

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
import play.api.libs.json.{ JsValue, JsArray, JsObject, Json }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class OrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "OrganizationController" should {
    "serve up organization views" in {
      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withHandle(OrganizationHandle("brewstercorp")).withName("Brewster Corp").withOwner(owner).saved
            (org, owner)
          }
          val publicId = PublicId[Organization]("2267")

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val request = route.getOrganization(publicId)
          val response = controller.getOrganization(publicId)(request)

          response === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
      "give an organization view to any user that has view permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, member, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withHandle(OrganizationHandle("brewstercorp")).withName("Brewster Corp").withOwner(owner).withMembers(Seq(member)).saved

            LibraryFactory.libraries(10).map(_.published().withOrganization(org.id)).saved
            LibraryFactory.libraries(15).map(_.withVisibility(LibraryVisibility.ORGANIZATION).withOrganization(org.id)).saved
            LibraryFactory.libraries(20).map(_.secret().withOrganization(org.id)).saved

            (org, owner, member, rando)
          }

          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val ownerRequest = route.getOrganization(publicId)
          val ownerResponse = controller.getOrganization(publicId)(ownerRequest)
          status(ownerResponse) === OK

          inject[FakeUserActionsHelper].setUser(member, Set(UserExperimentType.ORGANIZATION))
          val memberRequest = route.getOrganization(publicId)
          val memberResponse = controller.getOrganization(publicId)(memberRequest)
          status(memberResponse) === OK

          inject[FakeUserActionsHelper].setUser(rando, Set(UserExperimentType.ORGANIZATION))
          val randoRequest = route.getOrganization(publicId)
          val randoResponse = controller.getOrganization(publicId)(randoRequest)
          status(randoResponse) === OK

          val payloads = Seq(ownerResponse, memberResponse, randoResponse).map { response => Json.parse(contentAsString(response)) \ "organization" }

          payloads.foreach(p => (p \ "name").as[String] === "Brewster Corp")
          payloads.foreach(p => (p \ "handle").as[String] === "brewstercorp")
          1 === 1
        }
      }
    }
    "include the viewer's permissions along with the organization view" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (org, owner, member, rando) = db.readWrite { implicit session =>
          val owner = UserFactory.user().saved
          val member = UserFactory.user().saved
          val rando = UserFactory.user().saved
          val org = OrganizationFactory.organization().withHandle(OrganizationHandle("brewstercorp")).withName("Brewster Corp").withOwner(owner).withMembers(Seq(member)).saved
          (org, owner, member, rando)
        }
        val publicId = Organization.publicId(org.id.get)

        inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
        val ownerRequest = route.getOrganization(publicId)
        val ownerResponse = controller.getOrganization(publicId)(ownerRequest)
        status(ownerResponse) === OK
        (Json.parse(contentAsString(ownerResponse)) \ "viewer_permissions").as[Set[OrganizationPermission]] === org.basePermissions.forRole(OrganizationRole.OWNER)

        inject[FakeUserActionsHelper].setUser(member, Set(UserExperimentType.ORGANIZATION))
        val memberRequest = route.getOrganization(publicId)
        val memberResponse = controller.getOrganization(publicId)(memberRequest)
        status(memberResponse) === OK
        (Json.parse(contentAsString(memberResponse)) \ "viewer_permissions").as[Set[OrganizationPermission]] === org.basePermissions.forRole(OrganizationRole.MEMBER)

        inject[FakeUserActionsHelper].setUser(rando, Set(UserExperimentType.ORGANIZATION))
        val randoRequest = route.getOrganization(publicId)
        val randoResponse = controller.getOrganization(publicId)(randoRequest)
        status(randoResponse) === OK
        (Json.parse(contentAsString(randoResponse)) \ "viewer_permissions").as[Set[OrganizationPermission]] === org.basePermissions.forNonmember
      }
      "serve up the right number of libraries depending on viewer permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withHandle(OrganizationHandle("brewstercorp")).withName("Brewster Corp").withOwner(owner).saved

            LibraryFactory.libraries(10).map(_.published().withOrganization(org.id)).saved
            LibraryFactory.libraries(15).map(_.withVisibility(LibraryVisibility.ORGANIZATION).withOrganization(org.id)).saved
            LibraryFactory.libraries(20).map(_.secret().withOrganization(org.id)).saved

            (org, owner, rando)
          }
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val ownerRequest = route.getOrganization(publicId)
          val ownerResponse = controller.getOrganization(publicId)(ownerRequest)
          status(ownerResponse) === OK
          (Json.parse(contentAsString(ownerResponse)) \ "organization" \ "numLibraries").as[Int] === 10 + 15

          inject[FakeUserActionsHelper].setUser(rando, Set(UserExperimentType.ORGANIZATION))
          val randoRequest = route.getOrganization(publicId)
          val randoResponse = controller.getOrganization(publicId)(randoRequest)
          status(randoResponse) === OK
          (Json.parse(contentAsString(randoResponse)) \ "organization" \ "numLibraries").as[Int] === 10
        }
      }
    }

    "serve up organization cards" in {
      "give a user a list of organizations they belong to" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            for (i <- 1 to 10) {
              val org = OrganizationFactory.organization().withOwner(user).withName("Justice League").withHandle(OrganizationHandle("justiceleague" + i)).saved
              LibraryFactory.libraries(i).map(_.published().withUser(user).withOrganization(org.id)).saved
            }
            user
          }

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
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
      "hide a user's orgs based on viewer permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, rando) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val rando = UserFactory.user().saved
            for (i <- 1 to 10) {
              val org = OrganizationFactory.organization().withOwner(user).withName("Justice League").withHandle(OrganizationHandle("justiceleague" + i)).saved
              LibraryFactory.libraries(i).map(_.published().withUser(user).withOrganization(org.id)).saved
              if (i <= 5) {
                inject[OrganizationRepo].save(org.hiddenFromNonmembers)
              }
            }
            (user, rando)
          }

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
          val userRequest = route.getOrganizationsForUser(user.externalId)
          val userResponse = controller.getOrganizationsForUser(user.externalId)(userRequest)
          status(userResponse) === OK

          val userJsonResponse = Json.parse(contentAsString(userResponse))
          (userJsonResponse \ "organizations") must haveClass[JsArray]
          (userJsonResponse \ "organizations").as[Seq[JsValue]].length === 10

          inject[FakeUserActionsHelper].setUser(rando, Set(UserExperimentType.ORGANIZATION))
          val randoRequest = route.getOrganizationsForUser(user.externalId)
          val randoResponse = controller.getOrganizationsForUser(user.externalId)(randoRequest)
          status(randoResponse) === OK

          val randoJsonResponse = Json.parse(contentAsString(randoResponse))
          (randoJsonResponse \ "organizations") must haveClass[JsArray]
          (randoJsonResponse \ "organizations").as[Seq[JsValue]].length === 5
        }
      }
    }
    "serve up library cards for an org's libraries" in {
      def setupLibraries(numPublicLibs: Int, numOrgLibs: Int, numPrivateLibs: Int)(implicit injector: Injector) = db.readWrite { implicit session =>

        val owner = UserFactory.user().saved
        val member = UserFactory.user().saved
        val nonmember = UserFactory.user().saved

        val org = OrganizationFactory.organization()
          .withName("Moneybags, LLC")
          .withOwner(owner)
          .withMembers(Seq(member))
          .saved

        val publicLibs = LibraryFactory.libraries(numPublicLibs).map(_.published().withUser(owner).withOrganization(org.id)).saved
        val orgLibs = LibraryFactory.libraries(numOrgLibs).map(_.withVisibility(LibraryVisibility.ORGANIZATION).withUser(owner).withOrganization(org.id)).saved
        val privateLibs = LibraryFactory.libraries(numPrivateLibs).map(_.secret().withUser(member).withOrganization(org.id)).saved
        (org, owner, member, nonmember, publicLibs, orgLibs, privateLibs)
      }

      "give all org libraries to a member" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (numPublicLibs, numOrgLibs, numPrivateLibs) = (11, 27, 42)
          val (org, owner, member, nonmember, publicLibs, orgLibs, privateLibs) = setupLibraries(numPublicLibs, numOrgLibs, numPrivateLibs)

          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val request = route.getOrganizationLibraries(publicId, offset = 0, limit = 100)
          val response = controller.getOrganizationLibraries(publicId, offset = 0, limit = 100)(request)
          status(response) === OK

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "libraries") must haveClass[JsArray]
          (jsonResponse \ "libraries").as[Seq[JsValue]].length === numPublicLibs + numOrgLibs
        }
      }
      "give public libraries to a nonmember" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (numPublicLibs, numOrgLibs, numPrivateLibs) = (11, 27, 42)
          val (org, owner, member, nonmember, publicLibs, orgLibs, privateLibs) = setupLibraries(numPublicLibs, numOrgLibs, numPrivateLibs)

          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(nonmember, Set(UserExperimentType.ORGANIZATION))
          val request = route.getOrganizationLibraries(publicId, offset = 0, limit = 100)
          val response = controller.getOrganizationLibraries(publicId, offset = 0, limit = 100)(request)
          status(response) === OK

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "libraries") must haveClass[JsArray]
          (jsonResponse \ "libraries").as[Seq[JsValue]].length === numPublicLibs
        }
      }
      "give a member their private org-libraries" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (numPublicLibs, numOrgLibs, numPrivateLibs) = (11, 27, 42)
          val (org, owner, member, nonmember, publicLibs, orgLibs, privateLibs) = setupLibraries(numPublicLibs, numOrgLibs, numPrivateLibs)

          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(member, Set(UserExperimentType.ORGANIZATION))
          val request = route.getOrganizationLibraries(publicId, offset = 0, limit = 100)
          val response = controller.getOrganizationLibraries(publicId, offset = 0, limit = 100)(request)
          status(response) === OK

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "libraries") must haveClass[JsArray]
          (jsonResponse \ "libraries").as[Seq[JsValue]].length === numPublicLibs + numOrgLibs + numPrivateLibs
        }
      }
    }
    "create an organization" in {
      "reject malformed input" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
          val request = route.createOrganization().withBody(Json.parse("""{"asdf": "qwer"}"""))
          val result = controller.createOrganization(request)
          status(result) === BAD_REQUEST
        }
      }
      "reject empty names" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
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
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
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
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
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
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
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
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val request = route.deleteOrganization(publicId)
          val result = controller.deleteOrganization(publicId)(request)
          status(result) === NO_CONTENT
        }
      }

      "fail for non-owners" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, _, member) = setupDelete
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(member, Set(UserExperimentType.ORGANIZATION))
          val request = route.deleteOrganization(publicId)
          val result = controller.deleteOrganization(publicId)(request)

          result === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }
    }
    "when transferOrganization is called:" in {
      def setupTransfer(implicit injector: Injector) = db.readWrite { implicit session =>
        val owner = UserFactory.user().withName("Dr", "Papaya").saved
        val member = UserFactory.user().withName("Hansel", "Schmidt").saved
        val rando = UserFactory.user().withName("Rando", "McRanderson").saved
        val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).withName("Papaya Republic of California").withHandle(OrganizationHandle("papaya_republic")).saved
        (org, owner, member, rando)
      }

      "succeed for owner to member" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, member, rando) = setupTransfer
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val body = Json.obj("newOwner" -> member.externalId)
          val request = route.transferOrganization(publicId).withBody(body)
          val result = controller.transferOrganization(publicId)(request)
          status(result) === NO_CONTENT
          db.readOnlyMaster { implicit session =>
            inject[OrganizationRepo].get(org.id.get).ownerId === member.id.get
            inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, owner.id.get).get.role === OrganizationRole.OWNER
            inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, member.id.get).get.role === OrganizationRole.OWNER
          }
        }
      }
      "succeed for owner to non-member" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, member, rando) = setupTransfer
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val body = Json.obj("newOwner" -> rando.externalId)
          val request = route.transferOrganization(publicId).withBody(body)
          val result = controller.transferOrganization(publicId)(request)
          status(result) === NO_CONTENT
          db.readOnlyMaster { implicit session =>
            inject[OrganizationRepo].get(org.id.get).ownerId === rando.id.get
            inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, owner.id.get).get.role === OrganizationRole.OWNER
            inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, rando.id.get).get.role === OrganizationRole.OWNER
          }
        }
      }
      "fail for member to anyone" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, member, rando) = setupTransfer
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(member, Set(UserExperimentType.ORGANIZATION))
          val body1 = Json.obj("newOwner" -> member.externalId)
          val request1 = route.transferOrganization(publicId).withBody(body1)
          val result1 = controller.transferOrganization(publicId)(request1)
          status(result1) === FORBIDDEN

          val body2 = Json.obj("newOwner" -> rando.externalId)
          val request2 = route.transferOrganization(publicId).withBody(body2)
          val result2 = controller.transferOrganization(publicId)(request2)
          status(result2) === FORBIDDEN

          db.readOnlyMaster { implicit session =>
            inject[OrganizationRepo].get(org.id.get).ownerId === owner.id.get
            inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, owner.id.get).get.role === OrganizationRole.OWNER
            inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, member.id.get).get.role === OrganizationRole.MEMBER
          }
        }
      }
    }
  }

  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[OrganizationController]
  private def route = com.keepit.controllers.website.routes.OrganizationController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
      contentType(result) must beSome("application/json")
    }
  }
}
