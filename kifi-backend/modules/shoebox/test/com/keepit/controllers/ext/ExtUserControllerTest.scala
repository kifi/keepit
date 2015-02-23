package com.keepit.controllers.ext

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.UserPersonaCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Result, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import scala.concurrent.Await
import scala.concurrent.duration._

import scala.concurrent.Future

class ExtUserControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {
  implicit val context = HeimdalContext.empty
  val controllerTestModules = Seq(
    FakeCryptoModule(),
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeSliderHistoryTrackerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeHttpClientModule()
  )

  "ExtUserController" should {
    "get guide info" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user1 = db.readWrite { implicit s =>
          personaRepo.save(Persona(name = PersonaName.TECHIE, displayName = "techie", displayNamePlural = "techies", iconPath = "0.jpg", activeIconPath = "1.jpg"))
          user().withName("peter", "parker").withUsername("spiderman").saved
        }
        implicit val config = inject[PublicIdConfiguration]

        // get guide info with no persona
        val result1 = getGuideInfo(user1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        Json.parse(contentAsString(result1)) === Json.parse(
          s"""
            {
              "keep":{
                "url":"http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die",
                "imagePath":"/img/guide/ted_jobs.jpg",
                "imageWidth":480,
                "imageHeight":425,
                "noun":"video",
                "query":"steve+jobs",
                "title":"Steve Jobs: How to live before you die | Talk Video | TED.com",
                "matches":{"title":[[0,5],[6,4]],"url":[[25,5],[31,4]]},"track":"steveJobsSpeech"
              }
            }
           """)

        // get guide info with a persona
        val (_, personaLibOpt) = Await.result(inject[UserPersonaCommander].addPersonaForUser(user1.id.get, PersonaName.TECHIE), FiniteDuration(5, SECONDS))
        personaLibOpt.nonEmpty === true
        db.readOnlyMaster { implicit s =>
          libraryRepo.getByUser(user1.id.get).length === 1
        }

        val result2 = getGuideInfo(user1)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) === Json.parse(
          s"""
            {
              "keep":{
                "url":"http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die",
                "imagePath":"/img/guide/ted_jobs.jpg",
                "imageWidth":480,
                "imageHeight":425,
                "noun":"video",
                "query":"steve+jobs",
                "title":"Steve Jobs: How to live before you die | Talk Video | TED.com",
                "matches":{"title":[[0,5],[6,4]],"url":[[25,5],[31,4]]},"track":"steveJobsSpeech"
              },
              "library":{
                "id":"${Library.publicId(personaLibOpt.get.id.get).id}",
                "name":"Techie Picks",
                "color":"${personaLibOpt.get.color.get.hex}"
              }
            }
           """)
      }
    }
  }

  private def getGuideInfo(user: User)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getGuideInfo()(request(routes.ExtUserController.getGuideInfo()))
  }
  private def controller(implicit injector: Injector) = inject[ExtUserController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}
