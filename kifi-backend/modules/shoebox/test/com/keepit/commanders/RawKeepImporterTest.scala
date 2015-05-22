package com.keepit.commanders

import java.io.File

import com.keepit.common.strings.UTF8

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ FakeSlickModule, TestDbInfo }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext, HeimdalQueueDevModule }
import com.keepit.model._
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ AbuseControlModule, FakeShoeboxServiceClientModule, KeepImportsModule }
import com.keepit.test._
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json

class RawKeepImporterTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  // This is a good example of how to test actor side effects.
  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSimpleQueueModule(),
    HeimdalQueueDevModule(),
    FakeNormalizationUpdateJobQueueModule(),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    KeepImportsModule(),
    FakeActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxServiceClientModule(),
    FakeHttpClientModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    AbuseControlModule(),
    FakeCuratorServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "RawKeepImporter" should {

    "properly handle imports" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        val json = Json.parse(io.Source.fromFile(new File("test/data/bookmarks_small.json"), UTF8).mkString)
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.bookmarkImport, json, libraryId = None))

        // Importer is run synchronously in TestKit.

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          val bookmarks = keepRepo.all
          val oneUrl = bookmarks.find(_.url == "http://www.findsounds.com/types.html")
          // println(bookmarks) // can be removed?
          oneUrl.size === 1
          val bm = oneUrl.head
          bm.userId === user.id.get
          bm.isPrivate === true
          bookmarks.size === 5
        }
      }
    }

    "handle imports to library" in {
      withDb(modules: _*) { implicit injector =>
        val (user, lib) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "Shanee", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          val lib = libraryRepo.save(Library(name = "Lib1", ownerId = user.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
          (user, lib)
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        val json = Json.parse(io.Source.fromFile(new File("test/data/bookmarks_small.json"), UTF8).mkString)
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.bookmarkImport, json, libraryId = Some(lib.id.get)))

        // Importer is run synchronously in TestKit.

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          val bookmarks = keepRepo.all
          bookmarks.count(k => k.libraryId == lib.id) === 5
          val oneUrl = bookmarks.find(_.url == "http://www.findsounds.com/types.html")
          // println(bookmarks) // can be removed?
          oneUrl.size === 1
          val bm = oneUrl.head
          bm.userId === user.id.get
          bookmarks.size === 5

          collectionRepo.getAllTagsByUserSortedByNumKeeps(user.id.get).map(_._1.tag) === Seq("college", "hack", "stuff")
          keepToCollectionRepo.getCollectionsForKeep(bookmarks.filter(b => b.title.get.contains("Infographic")).head).length === 3
        }
      }
    }
  }

}
