package com.keepit.commanders

import java.io.File

import com.keepit.common.strings.UTF8

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ FakeSlickModule, TestDbInfo }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext, HeimdalQueueDevModule }
import com.keepit.model._
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ AbuseControlModule, FakeShoeboxServiceClientModule, KeepImportsModule }
import com.keepit.test._
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json
import com.keepit.model.{ UserFactory, LibraryFactory }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._

class RawKeepImporterTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  // This is a good example of how to test actor side effects.
  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule(),
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
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "RawKeepImporter" should {

    args(skipAll = true)

    "properly handle imports" in {
      withDb(modules: _*) { implicit injector =>
        val (user, lib) = db.readWrite { implicit session =>
          val u = UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved;
          val l = LibraryFactory.library().withOwner(u.id.get).saved

          (u, l)
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[RawKeepInterner]
        val json = Json.parse(io.Source.fromFile(new File("test/data/bookmarks_small.json"), UTF8).mkString)
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.BookmarkImport, json, libraryId = lib.id))

        // Importer is run synchronously in TestKit.

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          val bookmarks = keepRepo.aTonOfRecords
          val oneUrl = bookmarks.find(_.url == "http://www.findsounds.com/types.html")
          oneUrl.size === 1
          val bm = oneUrl.head
          bm.userId.get === user.id.get
          bookmarks.size === 5
        }
      }
    }

    "handle imports to library" in {
      withDb(modules: _*) { implicit injector =>
        val (user, lib) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
          val lib = libraryRepo.save(Library(name = "Lib1", ownerId = user.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
          (user, lib)
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[RawKeepInterner]
        val json = Json.parse(io.Source.fromFile(new File("test/data/bookmarks_small.json"), UTF8).mkString)
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.BookmarkImport, json, libraryId = Some(lib.id.get)))

        // Importer is run synchronously in TestKit.

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          val bookmarks = keepRepo.aTonOfRecords
          bookmarks.count(k => k.recipients.libraries.contains(lib.id.get)) === 5
          val oneUrl = bookmarks.find(_.url == "http://www.findsounds.com/types.html")
          oneUrl.size === 1
          val bm = oneUrl.head
          bm.userId.get === user.id.get
          bookmarks.size === 5

          tagCommander.tagsForUser(user.id.get, 0, 20, TagSorting.NumKeeps).map(_.name).toSet === Set("college", "hack", "stuff")
          tagCommander.getTagsForKeep(bookmarks.filter(b => b.title.get.contains("Infographic")).head.id.get).length === 3
        }
      }
    }
  }

}
