package com.keepit.controllers.internal

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model._
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.util.Try

class ShoeboxDiscussionControllerTest extends Specification with ShoeboxTestInjector {
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  val modules = Seq(
    DevDataPipelineExecutorModule(),
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeUserActionsModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule()
  )

  "ShoeboxDiscussionController" should {
    "create a keep" in {
      "with the correct information" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, lib) = db.readWrite { implicit s =>
            val owner = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(owner).saved
            (owner, lib)
          }
          val extRawKeep = KeepCreateRequest(
            owner = owner.externalId,
            users = Set(owner.externalId),
            libraries = Set(Library.publicId(lib.id.get)),
            url = "http://www.kifi.com",
            title = Some("Kifi!"),
            canonical = None,
            openGraph = None,
            keptAt = Some(fakeClock.now),
            note = Some("cool beans")
          )
          val result = inject[ShoeboxDiscussionController].internKeep()(FakeRequest().withBody(Json.toJson(extRawKeep)))
          status(result) must equalTo(OK)
          val keep = contentAsJson(result).as[CrossServiceKeep]

          db.readOnlyMaster { implicit s =>
            val dbKeep = keepRepo.get(keep.id)
            keep.url === dbKeep.url
            keep.uriId === dbKeep.uriId
            keep.title === dbKeep.title
            keep.note === dbKeep.note
            keep.owner === owner.id.get
            keep.users === Set(owner.id.get)
            keep.libraries === Set(lib.id.get)

            keep.users.foreach { uid => ktuRepo.getByKeepIdAndUserId(keep.id, uid) must beSome }
            keep.libraries.foreach { lid => ktlRepo.getByKeepIdAndLibraryId(keep.id, lid) must beSome }
          }
          1 === 1
        }
      }
      "handle bad permissions correctly" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, rando, lib) = db.readWrite { implicit s =>
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(owner).saved
            (owner, rando, lib)
          }
          val happyRawKeep = KeepCreateRequest(
            owner = owner.externalId,
            users = Set(owner.externalId),
            libraries = Set(Library.publicId(lib.id.get)),
            url = "http://www.kifi.com",
            title = Some("Kifi!"),
            canonical = None,
            openGraph = None,
            keptAt = Some(fakeClock.now),
            note = Some("cool beans")
          )
          val sadRawKeep = happyRawKeep.copy(owner = rando.externalId, users = Set(rando.externalId))

          status(inject[ShoeboxDiscussionController].internKeep()(FakeRequest().withBody(Json.toJson(sadRawKeep)))) === FORBIDDEN
          db.readOnlyMaster { implicit s => keepRepo.all must beEmpty }

          status(inject[ShoeboxDiscussionController].internKeep()(FakeRequest().withBody(Json.toJson(happyRawKeep)))) === OK
          db.readOnlyMaster { implicit s => keepRepo.all must haveSize(1) }
          1 === 1
        }
      }
    }
  }
}
