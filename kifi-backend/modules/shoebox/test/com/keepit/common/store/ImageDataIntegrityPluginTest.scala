package com.keepit.common.store

import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.common.net.{ DirectUrl, FakeClientResponse, FakeHttpClientModule }
import com.keepit.inject._
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class ImageDataIntegrityPluginTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val imageDataIntegrityTestPluginModule =
    new FakeShoeboxStoreModule() {
      override def configure() {
        bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
        bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "//cloudfront", isLocal = false))
      }
    }

  val modules = Seq(
    imageDataIntegrityTestPluginModule,
    FakeActorSystemModule(),
    FakeHttpClientModule(Map(
      DirectUrl("http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg") ->
        FakeClientResponse("image", 200),
      DirectUrl("http://s3.amazonaws.com/test-bucket/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg") ->
        FakeClientResponse("image", 404),
      DirectUrl("http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/100/0.jpg") ->
        FakeClientResponse("image", 400),
      DirectUrl("http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg") ->
        FakeClientResponse("image", 404)
    )))

  "The image data integrity plugin" should {
    "verify all pictures" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Greg", "Methvin").withUsername("test").withId("59eba923-54cb-4257-9bb6-7c81d602bd76").saved
          userPictureRepo.save(UserPicture(userId = user.id.get, name = "0", origin = UserPictureSources.FACEBOOK, attributes = None))
        }

        inject[ImageDataIntegrityPlugin].verifyAll()

        val errors = inject[FakeAirbrakeNotifier].errors
        // println("--------------------------") // can be removed?
        // println(errors mkString "\n") // can be removed?
        // println("--------------------------") // can be removed?
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
        } === false
        errors.exists {
          _.message.get contains
            "http://cloudfront/users/59eba923-54cb-4257-9bb6-7c81d602bd76/pics/200/0.jpg"
        } === false
      }
    }
  }
}
