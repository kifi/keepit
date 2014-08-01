package com.keepit.common.store

import org.specs2.mutable.SpecificationLike

import com.keepit.common.db.ExternalId
import com.keepit.common.net.{ FakeHttpClientModule, FakeClientResponse, DirectUrl }
import com.keepit.inject._
import com.keepit.model.{ UserPictureSources, UserPicture, User }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import com.keepit.common.mail.TestMailModule

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.test.Helpers.running
import com.keepit.common.actor.{ TestKitSupport, TestActorSystemModule }
import com.keepit.common.social.{ FakeSocialGraphModule, TestShoeboxAppSecureSocialModule }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeAirbrakeNotifier }
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.scraper.{ TestScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class ImageDataIntegrityPluginTest extends TestKitSupport with SpecificationLike with ShoeboxApplicationInjector {

  val imageDataIntegrityTestPluginModule =
    new ShoeboxFakeStoreModule() {
      override def configure() {
        bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
        bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "//cloudfront", isLocal = false))
      }
    }

  "The image data integrity plugin" should {
    "verify all pictures" in {
      running(new ShoeboxApplication(
        FakeAirbrakeModule(),
        TestSearchServiceClientModule(),
        FakeScrapeSchedulerModule(),
        imageDataIntegrityTestPluginModule,
        TestActorSystemModule(Some(system)),
        TestShoeboxAppSecureSocialModule(),
        FakeSocialGraphModule(),
        TestHeimdalServiceClientModule(),
        TestMailModule(),
        FakeExternalServiceModule(),
        FakeCortexServiceClientModule(),
        TestScraperServiceClientModule(),
        FakeHttpClientModule(Map(
          DirectUrl("http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg") ->
            FakeClientResponse("image", 200),
          DirectUrl("http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg") ->
            FakeClientResponse("image", 404),
          DirectUrl("http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg") ->
            FakeClientResponse("image", 400),
          DirectUrl("http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg") ->
            FakeClientResponse("image", 404)
        )))) {
        db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Greg", lastName = "Methvin",
            externalId = ExternalId("59eba923-54cb-4257-9bb6-7c81d602bd76")))
          userPictureRepo.save(UserPicture(userId = user.id.get, name = "0", origin = UserPictureSources.FACEBOOK, attributes = None))
        }

        inject[ImageDataIntegrityPlugin].verifyAll()

        val errors = inject[FakeAirbrakeNotifier].errors
        println("--------------------------")
        println(errors mkString "\n")
        println("--------------------------")
        errors.exists {
          _.message.get contains
            "http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg"
        } === false
        errors.exists {
          _.message.get contains
            "http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg"
        } === true
        errors.exists {
          _.message.get contains
            "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg"
        } === true
        errors.exists {
          _.message.get contains
            "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg"
        } === false
      }
    }
  }
}
