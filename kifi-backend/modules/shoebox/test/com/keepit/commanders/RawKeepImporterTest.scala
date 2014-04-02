package com.keepit.commanders

import org.specs2.mutable.SpecificationLike
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}
import com.keepit.heimdal.HeimdalContext
import akka.actor.ActorSystem
import com.keepit.model.{RawKeepFactory, KeepSource, User}
import play.api.libs.json.Json
import akka.testkit.{TestActorRef, TestKit}
import play.api.test.Helpers._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.shoebox.{TestShoeboxServiceClientModule, KeepImportsModule, FakeKeepImportsModule}
import com.keepit.common.actor.{ActorBuilder, TestActorSystemModule}
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.common.net.FakeHttpClientModule
import java.io.File


class RawKeepImporterTest extends TestKit(ActorSystem()) with SpecificationLike with ShoeboxApplicationInjector {
  // This is a good example of how to test actor side effects.
  implicit val context = HeimdalContext.empty

  def modules = KeepImportsModule() :: TestActorSystemModule() :: TestSearchServiceClientModule() :: TestShoeboxServiceClientModule() :: FakeHttpClientModule() :: FakeScrapeSchedulerModule() :: Nil

  "RawKeepImporter" should {

    "properly handle imports" in {
      running(new ShoeboxApplication(modules: _*)) {
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
