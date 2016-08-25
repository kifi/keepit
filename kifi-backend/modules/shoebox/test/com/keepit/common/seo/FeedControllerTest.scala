package com.keepit.common.seo

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.controllers.website.FeedController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.xml._

class FeedControllerTest extends Specification with ShoeboxTestInjector {

  args(skipAll = true)

  val modules = Seq(
    FakeCryptoModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeKeepImportsModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxServiceModule()
  )

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = UserFactory.user().withName("Aaron", "H").withCreatedAt(t1).withUsername("aaron").saved
      val user2 = UserFactory.user().withName("Jackie", "Chan").withCreatedAt(t1.plusHours(2)).withUsername("jackie").saved

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

      val s1 = libraryRepo.save(Library(name = Library.SYSTEM_MAIN_DISPLAY_NAME, ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("main"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val s2 = libraryRepo.save(Library(name = Library.SYSTEM_SECRET_DISPLAY_NAME, ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_SECRET, slug = LibrarySlug("secret"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
      val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/foo", Some("AmazonFoo")))

      val hover = KeepSource.Keeper

      KeepFactory.keep().withTitle("G1").withUser(user1).withUri(uri1).withLibrary(lib1).saved
      KeepFactory.keep().withTitle("A1").withUser(user1).withUri(uri2).withLibrary(lib1).saved
      KeepFactory.keep().withTitle("A2").withUser(user1).withUri(uri3).withLibrary(lib1).saved
      KeepFactory.keep().withUser(user1).withUri(uri1).withLibrary(lib1).saved

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
        (items.head \ "link").text.trim === s"http://dev.ezkeep.com:9000${LibraryPathHelper.formatLibraryPath(u2.username, None, lib3.slug)}"

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
        (items.head \ "link").text.trim === s"http://dev.ezkeep.com:9000${LibraryPathHelper.formatLibraryPath(u1.username, None, lib1.slug)}"

      }
    }
  }
}
