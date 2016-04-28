package com.keepit.shoebox.data.keep

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.common.util.TestHelpers.matchJson
import com.keepit.model.KeepFactoryHelper.KeepPersister
import com.keepit.model.LibraryFactoryHelper.LibraryPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.social.{ BasicAuthor, BasicNonUser, BasicUser }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.SpecificationLike
import play.api.libs.json.Json

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
          val info = Await.result(inject[KeepInfoAssembler].assembleKeepInfos(viewer = Some(user.id.get), keepSet = Set(keep.id.get)), Duration.Inf)(keep.id.get).getRight.get
          // TODO(cam): when the activity log is released, uncomment this line and use it in `expected`
          // val activity = Await.result(keepCommander.getActivityForKeep(keep.id.get, None, 5), Duration.Inf)
          val actual = NewKeepInfo.writes.writes(info)
          val expected = Json.obj(
            "id" -> Keep.publicId(keep.id.get),
            "path" -> keep.path.relativeWithLeadingSlash,
            "url" -> keep.url,
            "title" -> keep.title,
            // "imagePath" -> JsNull,
            "author" -> BasicAuthor.fromUser(BasicUser.fromUser(user)),
            "keptAt" -> DateTimeJsonFormat.writes(keep.keptAt),
            // "source" -> JsNull,
            "recipients" -> Json.obj(
              "users" -> Seq(BasicUser.fromUser(user)),
              "emails" -> Seq.empty[BasicContact],
              "libraries" -> Seq(BasicLibrary(lib, BasicUser.fromUser(user), None, None))
            ),
            "viewer" -> Json.obj(
              "permissions" -> db.readOnlyMaster { implicit s => permissionCommander.getKeepPermissions(keep.id.get, Some(user.id.get)) }
            )
          )
          actual must matchJson(expected)
        }
      }
    }
  }
}
