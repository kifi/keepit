package com.keepit.controllers.email

import java.util.NoSuchElementException

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class EmailRecosControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeScrapeSchedulerModule(),
    FakeCuratorServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeAirbrakeModule()
  )

  "EmailRecosController" should {
    "viewReco" should {
      "redirect to the page" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val userRepo = inject[UserRepo]
          val uriRepo = inject[NormalizedURIRepo]
          val urlRepo = inject[URLRepo]
          val controller = inject[EmailRecosController]

          val (user1, uri1) = db.readWrite { implicit s =>
            val user1 = userRepo.save(User(firstName = "Jo", lastName = "Bennett"))
            uriRepo.count === 0
            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.website.com/article1", Some("Article1")))
            (user1, uri1)
          }

          val call = com.keepit.controllers.email.routes.EmailRecosController.viewReco(uri1.externalId)
          call.toString === s"/e/1/recos/view?id=${uri1.externalId}"

          val result = controller.viewReco(uri1.externalId)(FakeRequest())
          header("Location", result).get === uri1.url
        }
      }
    }

    "sendReco" should {
      "render a page that loads the URL and extension" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val userRepo = inject[UserRepo]
          val uriRepo = inject[NormalizedURIRepo]
          val urlRepo = inject[URLRepo]
          val controller = inject[EmailRecosController]
          val uri1 = db.readWrite { implicit s =>
            val user1 = userRepo.save(User(firstName = "Jo", lastName = "Bennett"))
            uriRepo.count === 0
            uriRepo.save(NormalizedURI.withHash("http://www.website.com/article1", Some("Article1")))
          }
          val call = com.keepit.controllers.email.routes.EmailRecosController.sendReco(uri1.externalId)
          call.toString === s"/e/1/recos/send?id=${uri1.externalId}"
          val result = controller.sendReco(uri1.externalId)(FakeRequest())
          status(result) === OK
          val htmlBody = contentAsString(result)
          htmlBody must contain("http://www.website.com/article1")
          htmlBody must contain("\"#compose\"")
        }
      }
    }

    "addKeep" should {
      "persist a new keep for authenticated user" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val userRepo = inject[UserRepo]
          val uriRepo = inject[NormalizedURIRepo]
          val urlRepo = inject[URLRepo]
          val controller = inject[EmailRecosController]

          val uri1 = db.readWrite { implicit rw =>
            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.scala-lang.org/documentation", Some("Scala")))
            keepRepo.getByUser(Id[User](1)).size === 0
            uri1
          }

          val call = com.keepit.controllers.email.routes.EmailRecosController.addKeep(uri1.externalId)
          call.toString === s"/e/1/recos/keep?id=${uri1.externalId}"

          val result = controller.addKeep(uri1.externalId)(FakeRequest())
          db.readOnlyMaster { implicit session =>
            val keeps = keepRepo.getByUser(Id[User](1))
            keeps.size === 1
            keeps(0).uriId === uri1.id.get
            keeps(0).source === KeepSource.emailReco
            keeps(0).isActive === true
            keeps(0).isPrivate === false
          }

          status(result) === SEE_OTHER
          header("Location", result).get === com.keepit.controllers.website.routes.HomeController.kifeeeed().toString
        }
      }

      "returns not found for bad URI" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val extId = ExternalId[NormalizedURI](java.util.UUID.randomUUID.toString) // another UUID bites the dust
          val controller = inject[EmailRecosController]
          controller.addKeep(extId)(FakeRequest()) must throwA[NoSuchElementException]
        }
      }
    }
  }
}

