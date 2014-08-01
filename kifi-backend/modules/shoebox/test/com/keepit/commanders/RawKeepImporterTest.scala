package com.keepit.commanders

import java.io.File

import com.keepit.common.actor.{ StandaloneTestActorSystemModule, FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.db.{ FakeSlickModule, TestDbInfo }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext, HeimdalQueueDevModule }
import com.keepit.model.{ KeepSource, RawKeepFactory, UrlPatternRuleModule, User }
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ AbuseControlModule, FakeShoeboxServiceClientModule, KeepImportsModule }
import com.keepit.test._
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json

class RawKeepImporterTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  // This is a good example of how to test actor side effects.
  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeSlickModule(TestDbInfo.dbInfo),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    UrlPatternRuleModule(),
    FakeSimpleQueueModule(),
    HeimdalQueueDevModule(),
    FakeNormalizationUpdateJobQueueModule(),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    KeepImportsModule(),
    StandaloneTestActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxServiceClientModule(),
    FakeHttpClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    AbuseControlModule()
  )

  "RawKeepImporter" should {

    "properly handle imports" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[KeepInterner]
        val json = Json.parse(io.Source.fromFile(new File("test/data/bookmarks_small.json")).mkString)
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.bookmarkImport, json))

        // Importer is run synchronously in TestKit.

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          val bookmarks = keepRepo.all
          val oneUrl = bookmarks.find(_.url == "http://www.findsounds.com/types.html")
          println(bookmarks)
          oneUrl.size === 1
          val bm = oneUrl.head
          bm.userId === user.id.get
          bm.isPrivate === true
          bookmarks.size === 5
        }
      }
    }
  }

}
