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
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibrarySitemapTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    FakeCuratorServiceClientModule()
  )

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val u1 = User(firstName = "Aaron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test")
    val u2 = User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2), username = Username("test"), normalizedUsername = "test")

    db.readWrite { implicit s =>
      val user1 = userRepo.save(u1)
      val user2 = userRepo.save(u2)

      val lib1 = libraryRepo.save(Library(name = "lib1A", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A"), memberCount = 1, lastKept = Some(new DateTime(2015, 3, 16, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE))))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val lib2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B"), memberCount = 1, lastKept = None))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))

      val lib3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("C"), memberCount = 1, lastKept = None))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))

      val s1 = libraryRepo.save(Library(name = "Main Library", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("main"), memberCount = 1, lastKept = Some(t1)))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val s2 = libraryRepo.save(Library(name = "Secret Library", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_SECRET, slug = LibrarySlug("secret"), memberCount = 1, lastKept = Some(t1)))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
      val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/foo", Some("AmazonFoo")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      val hover = KeepSource.keeper

      keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      keepRepo.save(Keep(title = Some("A2"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri3.id.get, source = hover, createdAt = t1.plusHours(50),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = hover, createdAt = t1.plusDays(1),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

      lib1
    }
  }

  "Sitemap" should {
    "basically work" in { // test read/write/save
      withDb(modules: _*) { implicit injector =>
        val lib = setup()
        val sitemap = Await.result(inject[LibrarySiteMapGenerator].generate(), Duration.Inf)
        val updateAt = ISO_8601_DAY_FORMAT.print(lib.updatedAt)

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
