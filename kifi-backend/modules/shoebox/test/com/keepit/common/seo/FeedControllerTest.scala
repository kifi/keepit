package com.keepit.common.seo

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.controllers.website.FeedController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScrapeSchedulerConfigModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.xml._

class FeedControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeCryptoModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeKeepImportsModule(),
    FakeMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerConfigModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxServiceModule()
  )

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val u1 = User(firstName = "Aaron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test")
    val u2 = User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2), username = Username("test"), normalizedUsername = "test")

    db.readWrite { implicit s =>
      val user1 = userRepo.save(u1)
      val user2 = userRepo.save(u2)

      val lib1 = libraryRepo.save(Library(name = "lib1A", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val lib2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))

      val lib3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(3), slug = LibrarySlug("C"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))

      val s1 = libraryRepo.save(Library(name = "Main Library", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("main"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val s2 = libraryRepo.save(Library(name = "Secret Library", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_SECRET, slug = LibrarySlug("secret"), memberCount = 1))
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

      (user1, user2, lib1, lib2, lib3)
    }
  }

  "FeedController" should {
    "supports new libraries feed" in {
      withDb(modules: _*) { implicit injector =>
        val (u1, u2, lib1, lib2, lib3) = setup()
        val path = com.keepit.controllers.website.routes.FeedController.getNewLibraries().toString()
        path === "/feeds/libraries/new"

        val feedController = inject[FeedController]
        val request = FakeRequest("GET", path)
        val result = feedController.getNewLibraries()(request)
        status(result) === OK
        contentType(result) === Some("application/rss+xml")
        val stringResult = contentAsString(result)
        val elem = XML.loadString(stringResult)
        val channel = (elem \ "channel")
        (channel \ "link").text.trim === s"http://dev.ezkeep.com:9000$path"
        val items = (elem \\ "item")
        items.size === 3
        (items.head \ "link").text.trim === s"http://dev.ezkeep.com:9000${Library.formatLibraryPath(u2.username, u2.externalId, lib3.slug)}"

      }
    }
    "supports top libraries feed" in {
      withDb(modules: _*) { implicit injector =>
        val (u1, u2, lib1, lib2, lib3) = setup()
        val path = com.keepit.controllers.website.routes.FeedController.getTopLibraries().toString()
        path === "/feeds/libraries/top"

        val feedController = inject[FeedController]
        val request = FakeRequest("GET", path)
        val result = feedController.getTopLibraries()(request)
        status(result) === OK
        contentType(result) === Some("application/rss+xml")
        val stringResult = contentAsString(result)
        val elem = XML.loadString(stringResult)
        val channel = (elem \ "channel")
        (channel \ "link").text.trim === s"http://dev.ezkeep.com:9000$path"
        val items = (elem \\ "item")
        items.size === 1
        (items.head \ "link").text.trim === s"http://dev.ezkeep.com:9000${Library.formatLibraryPath(u1.username, u1.externalId, lib1.slug)}"

      }
    }
  }
}
