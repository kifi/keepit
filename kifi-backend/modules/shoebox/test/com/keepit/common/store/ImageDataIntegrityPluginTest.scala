package com.keepit.common.store

import org.specs2.mutable.Specification

import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.common.net.{FakeHttpClientModule, FakeClientResponse, DirectUrl}
import com.keepit.inject._
import com.keepit.model.User
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.test.Helpers.running
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.social.{FakeSocialGraphModule, TestShoeboxSecureSocialModule}
import com.keepit.common.healthcheck.FakeAirbrakeModule

class ImageDataIntegrityPluginTest extends TestKit(ActorSystem()) with Specification with ShoeboxApplicationInjector {

  val imageDataIntegrityTestPluginModule =
    new FakeStoreModule() {
      override def configure() {
        bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
        bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "//cloudfront", isLocal = false))
      }
    }

  "The image data integrity plugin" should {
    "verify all pictures" in {
      running(new ShoeboxApplication(
        FakeAirbrakeModule(),
        imageDataIntegrityTestPluginModule,
        TestActorSystemModule(Some(system)),
        TestShoeboxSecureSocialModule(),
        FakeSocialGraphModule(),
        FakeHttpClientModule(Map(
        DirectUrl("http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg") ->
          FakeClientResponse("image", 200),
        DirectUrl("http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg") ->
          FakeClientResponse("image", 404),
        DirectUrl("http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg") ->
          FakeClientResponse("image", 400),
        DirectUrl("http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg") ->
          FakeClientResponse("image", 404)
      )))){
        db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Greg", lastName = "Methvin",
            externalId = ExternalId("59eba923-54cb-4257-9bb6-7c81d602bd76")))
        }

        inject[ImageDataIntegrityPlugin].verifyAll()

        val errors = inject[FakeHealthcheck].errors()
        errors.exists { _.errorMessage.get contains
          "http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg"
        } === false
        errors.exists { _.errorMessage.get contains
          "http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg"
        } === true
        errors.exists { _.errorMessage.get contains
          "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg"
        } === true
        errors.exists { _.errorMessage.get contains
          "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg"
        } === false
      }
    }
  }
}
