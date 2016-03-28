package com.keepit.model

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule

import com.keepit.common.mail.FakeMailModule
import com.keepit.common.seo.LibrarySiteMapGenerator
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibrarySitemapTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule()
  )

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = UserFactory.user().withCreatedAt(t1).withName("Aaron", "H").withUsername("test").saved
      val user2 = UserFactory.user().withCreatedAt(t1.plusHours(2)).withName("Jackie", "Chan").withUsername("test2").saved

      val lib1 = libraryRepo.save(Library(name = "lib1A", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A"), memberCount = 1, lastKept = Some(new DateTime(2015, 3, 16, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)), keepCount = 4))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val lib2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B"), memberCount = 1, lastKept = None))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))

      val lib3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("C"), memberCount = 1, lastKept = None))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))

      val s1 = libraryRepo.save(Library(name = Library.SYSTEM_MAIN_DISPLAY_NAME, ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("main"), memberCount = 1, lastKept = Some(t1)))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val s2 = libraryRepo.save(Library(name = Library.SYSTEM_SECRET_DISPLAY_NAME, ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_SECRET, slug = LibrarySlug("secret"), memberCount = 1, lastKept = Some(t1)))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
      val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/foo", Some("AmazonFoo")))

      KeepFactory.keep().withTitle("G1").withUser(user1).withUri(uri1).withLibrary(lib1).saved
      KeepFactory.keep().withTitle("A1").withUser(user1).withUri(uri2).withLibrary(lib1).saved
      KeepFactory.keep().withTitle("A2").withUser(user1).withUri(uri3).withLibrary(lib1).saved
      KeepFactory.keep().withUser(user2).withUri(uri1).withLibrary(lib1).saved
      libraryRepo.get(lib1.id.get)
    }
  }

  "Sitemap" should {
    "basically work" in { // test read/write/save
      withDb(modules: _*) { implicit injector =>
        val lib = setup()
        val sitemap = Await.result(inject[LibrarySiteMapGenerator].generate(), Duration.Inf)
        val updateAt = ISO_8601_DAY_FORMAT.print(lib.lastKept.get)

        sitemap.replaceAll(" ", "").trim ===
          s"""
            |<?xml-stylesheet type='text/xsl' href='sitemap.xsl'?>
            |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            |          <url>
            |                <loc>
            |                  http://dev.ezkeep.com:9000/test/A
            |                </loc>
            |                <lastmod>$updateAt</lastmod>
            |              </url>
            |        </urlset>
          """.stripMargin.replaceAll(" ", "").trim
      }
    }
  }
}
