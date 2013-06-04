package com.keepit.common.store

import org.specs2.mutable.Specification

import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.common.net.{FakeClientResponse, FakeHttpClient, HttpClient}
import com.keepit.inject._
import com.keepit.model.User
import com.keepit.test.{DbRepos, DevApplication}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers.running

class ImageDataIntegrityPluginTest extends TestKit(ActorSystem()) with Specification with DbRepos {
  "The image data integrity plugin" should {
    "verify all pictures" in {
      running(new DevApplication()
          .withFakePersistEvent().withFakeMail().withFakeHealthcheck().withFakeHttpClient().withFakeStore()
          .withTestActorSystem(system)
          .overrideWith(new FortyTwoModule {
            override def configure() {
              bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "//cloudfront", isLocal = false))
              bind[HttpClient].toInstance(new FakeHttpClient(Some(Map(
                "http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg" ->
                  FakeClientResponse("image", 200),
                "http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg" ->
                  FakeClientResponse("image", 404),
                "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg" ->
                  FakeClientResponse("image", 400),
                "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg" ->
                  FakeClientResponse("image", 404)
              ))))
            }
          })) {
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
