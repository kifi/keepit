package com.keepit.controllers.email

import java.util.NoSuchElementException

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class EmailRecosControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeCuratorServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeAirbrakeModule()
  )

  "EmailRecosController" should {
    "viewReco" should {
      def testViewRecoAction(isAuthenticated: Boolean) = {
        withDb(controllerTestModules: _*) { implicit injector =>
          val userRepo = inject[UserRepo]
          val uriRepo = inject[NormalizedURIRepo]
          val helper = inject[FakeUserActionsHelper]

          val (userOpt, uri1) = db.readWrite { implicit s =>
            val userOpt = if (isAuthenticated) Some(userRepo.save(User(firstName = "Jo", lastName = "Bennett", username = Username("test"), normalizedUsername = "test"))) else None
            uriRepo.count === 0
            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.website.com/article1", Some("Article1")))
            (userOpt, uri1)
          }

          val controller = inject[EmailRecosController]
          if (isAuthenticated) {
            helper.setUser(userOpt.get)
            inject[LibraryCommander].internSystemGeneratedLibraries(userOpt.get.id.get)
          }

          val call = com.keepit.controllers.email.routes.EmailRecosController.viewReco(uri1.externalId)
          call.toString === s"/r/e/1/recos/view?id=${uri1.externalId}"

          val result = controller.viewReco(uri1.externalId)(FakeRequest())
          header("Location", result).get === uri1.url
        }
      }

      "redirect to the page for authenticated user" in testViewRecoAction(true)
      "redirect to the page for unauthenticated user" in testViewRecoAction(false)
    }

    "sendReco" should {
      "render a page that loads the URL and extension" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val userRepo = inject[UserRepo]
          val uriRepo = inject[NormalizedURIRepo]
          val controller = inject[EmailRecosController]
          val uri1 = db.readWrite { implicit s =>
            uriRepo.count === 0
            uriRepo.save(NormalizedURI.withHash("http://www.website.com/article1", Some("Article1")))
          }
          inject[FakeUserActionsHelper].setUser(User(Some(Id[User](1L)), firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
          val call = com.keepit.controllers.email.routes.EmailRecosController.sendReco(uri1.externalId)
          call.toString === s"/r/e/1/recos/send?id=${uri1.externalId}"
          val result = controller.sendReco(uri1.externalId)(FakeRequest())
          status(result) === OK
          val htmlBody = contentAsString(result)
          htmlBody must contain("http://www.website.com/article1")
          htmlBody must contain("\"#compose\"")
        }
      }
    }

    "keepReco" should {
      "persist a new keep for authenticated user" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val uriRepo = inject[NormalizedURIRepo]
          val controller = inject[EmailRecosController]

          val (user1, uri1) = db.readWrite { implicit rw =>
            val user1 = userRepo.save(User(firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.scala-lang.org/documentation", Some("Scala")))
            keepRepo.getByUser(user1.id.get).size === 0
            (user1, uri1)
          }

          val call = com.keepit.controllers.email.routes.EmailRecosController.keepReco(uri1.externalId)
          call.toString === s"/r/e/1/recos/keep?id=${uri1.externalId}"

          inject[FakeUserActionsHelper].setUser(user1)
          val result = controller.keepReco(uri1.externalId)(FakeRequest())
          status(result) === SEE_OTHER // redirect
          db.readOnlyMaster { implicit session =>
            val keeps = keepRepo.getByUser(user1.id.get)
            keeps.size === 1
            keeps(0).uriId === uri1.id.get
            keeps(0).source === KeepSource.emailReco
            keeps(0).isActive === true
            keeps(0).isPrivate === false
          }

          status(result) === SEE_OTHER
          header("Location", result).get === "/"
        }
      }

      "returns not found for bad URI" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val extId = ExternalId[NormalizedURI](java.util.UUID.randomUUID.toString) // another UUID bites the dust
          inject[FakeUserActionsHelper].setUser(User(Some(Id[User](1L)), firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
          val controller = inject[EmailRecosController]
          val call = controller.keepReco(extId)(FakeRequest())
          status(call) === BAD_REQUEST
        }
      }
    }
  }
}

