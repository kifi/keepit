package com.keepit.controllers.client

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.util.Ord
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal._
import com.keepit.model.KeepFactoryHelper.KeepPersister
import com.keepit.model.LibraryFactoryHelper.LibraryPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model._
import com.keepit.search._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.JsValue
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.util.Random

class LibraryKeepsInfoControllerTest extends Specification with ShoeboxTestInjector {
  def rnd(lo: Int, hi: Int): Int = lo + Random.nextInt(hi - lo)
  implicit def pubIdConfig(implicit injector: Injector): PublicIdConfiguration = inject[PublicIdConfiguration]
  val modules = Seq(
    FakeUserActionsModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule()
  )
  "LibraryKeepsInfoController" should {
    "allow user-specified ordering" in {
      withDb(modules: _*) { implicit injector =>
        val now = fakeClock.now
        val (user, lib, keeps) = db.readWrite { implicit s =>
          val user = UserFactory.user().saved
          val lib = LibraryFactory.library().withOwner(user).saved
          val keeps = KeepFactory.keeps(20).map { k =>
            k.withLibrary(lib).withUser(user)
              .withKeptAt(now minusHours rnd(500, 1000))
              .withLastActivityAt(now minusHours rnd(50, 200))
              .saved
          }
          (user, lib, keeps)
        }

        val libId = Library.publicId(lib.id.get)
        inject[FakeUserActionsHelper].setUser(user)

        // bind query parameters correctly
        def query(qs: String): Seq[Id[Keep]] = {
          val request = FakeRequest("GET", com.keepit.controllers.client.routes.LibraryKeepsInfoController.getKeepsInLibrary(libId).url + "?" + qs)
          val result = inject[LibraryKeepsInfoController].getKeepsInLibrary(libId)(request)
          val payload = contentAsJson(result)
          (payload \ "keeps").as[Seq[JsValue]].map(kv => (kv \ "keep" \ "id").as[PublicId[Keep]]).map(kId => Keep.decodePublicId(kId).get)
        }

        query("") === keeps.sortBy(k => (k.lastActivityAt.getMillis, k.id.get.id))(Ord.descending).map(_.id.get).take(10)
        query("limit=1") === keeps.sortBy(k => (k.lastActivityAt.getMillis, k.id.get.id))(Ord.descending).map(_.id.get).take(1)
        query("orderBy=kept_at") === keeps.sortBy(k => (k.keptAt.getMillis, k.id.get.id))(Ord.descending).map(_.id.get).take(10)
        query("orderBy=kept_at&dir=asc") === keeps.sortBy(k => (k.keptAt.getMillis, k.id.get.id))(Ord.ascending).map(_.id.get).take(10)
        query(s"fromId=${Keep.publicId(keeps.head.id.get).id}&orderBy=kept_at&dir=asc") ===
          keeps.sortBy(k => (k.keptAt.getMillis, k.id.get.id))(Ord.ascending).map(_.id.get).dropWhile(_ != keeps.head.id.get).drop(1).take(10)

        // fail if the query parameters are invalid
        def err(qs: String) = {
          val request = FakeRequest("GET", com.keepit.controllers.client.routes.LibraryKeepsInfoController.getKeepsInLibrary(libId).url + "?" + qs)
          val result = inject[LibraryKeepsInfoController].getKeepsInLibrary(libId)(request)
          val payload = contentAsJson(result)
          (payload \ "error").as[String]
        }
        err("orderBy=garbage") === "malformed_input"
        err("dir=forward") === "malformed_input"
        err("orderBy=garbage&dir=asc") === "malformed_input"
        err("orderBy=kept_at&dir=forward") === "malformed_input"
      }
    }
  }
}
