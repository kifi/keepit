package com.keepit.shoebox.data.keep

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.json.TestHelper
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.model.KeepFactoryHelper.KeepPersister
import com.keepit.model.LibraryFactoryHelper.LibraryPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.social.{ BasicAuthor, BasicUser }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.SpecificationLike
import play.api.libs.json.{ JsNull, Json }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class KeepDataFormatTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit def s3Config(implicit injector: Injector) = inject[S3ImageConfig]
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )
  "KeepDataObjects" should {
    "serialize properly" in {
      "keep info" in {
        withDb(modules: _*) { implicit injector =>
          val (keep, user, lib) = db.readWrite { implicit s =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("foo bar").saved
            (keep, user, lib)
          }
          val Seq((info, viewerInfo)) = Await.result(inject[KeepInfoAssembler].assembleInfoForKeeps(viewer = Some(user.id.get), keepIds = Seq(keep.id.get)), Duration.Inf)
          val actual = NewKeepInfo.writes.writes(info)
          val expected = Json.obj(
            "id" -> Keep.publicId(keep.id.get),
            "path" -> keep.path.absolute,
            "url" -> keep.url,
            "title" -> keep.title,
            // "imagePath" -> JsNull,
            "author" -> BasicAuthor.fromUser(BasicUser.fromUser(user)),
            "keptAt" -> DateTimeJsonFormat.writes(keep.keptAt),
            // "source" -> JsNull,
            "users" -> Seq(BasicUser.fromUser(user)),
            "libraries" -> Seq(BasicLibrary(lib, BasicUser.fromUser(user), None)),
            "activity" -> KeepActivity.empty
          )
          TestHelper.deepCompare(actual, expected) must beNone
        }
      }
    }
  }
}