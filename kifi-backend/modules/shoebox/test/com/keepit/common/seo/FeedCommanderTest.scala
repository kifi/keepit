package com.keepit.common.seo

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FeedCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxServiceModule()
  )

  "Feed Commander" should {
    "create feed for library" in {
      withDb(modules: _*) { implicit injector =>
        val library = db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").saved
          val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val keep1 = KeepFactory.keep().withTitle("Google").withUser(user).withUri(uri1).withNote("Google Note").withLibrary(library).saved
          val keep2 = KeepFactory.keep().withTitle("Amazon").withUser(user).withUri(uri2).withNote("Amazon Note").withLibrary(library).saved
          library
        }
        val commander = inject[FeedCommander]
        val resultTry = Await.ready(commander.libraryFeed(library), Duration.Inf).value.get
        resultTry.isSuccess must equalTo(true)
        val result = resultTry.get
        (result \ "channel" \ "title").text must contain("test by Colin Lane")

        val amazon = (result \ "channel" \ "item")(0)
        (amazon \ "title").text must be equalTo ("Amazon")
        (amazon \ "description").text must be equalTo ("")

        val google = (result \ "channel" \ "item")(1)
        (google \ "title").text must be equalTo ("Google")
        (google \ "description").text must equalTo("") // we get our descriptions from rover not from the note.
        (google \ "link").text must equalTo("http://www.google.com/")
      }
    }

    "limit results by" in {
      "count" in {
        withDb(modules: _*) { implicit injector =>
          val (library) = db.readWrite { implicit s =>
            val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
            val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").saved
            val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
            val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

            val keep1 = KeepFactory.keep().withTitle("Google").withUser(user).withUri(uri1).withNote("Google Note").withLibrary(library).saved
            val keep2 = KeepFactory.keep().withTitle("Amazon").withUser(user).withUri(uri1).withLibrary(library).saved
            library
          }
          val commander = inject[FeedCommander]
          val resultTry = Await.ready(commander.libraryFeed(library, keepCountToDisplay = 1, offset = 0), Duration.Inf).value.get
          resultTry.isSuccess must equalTo(true)
          val result = resultTry.get
          (result \ "channel" \ "title").text must contain("test by Colin Lane")

          val items = (result \ "channel" \ "item")
          items.size must equalTo(1)

          val amazon = items(0)
          (amazon \ "title").text must be equalTo ("Amazon")
          (amazon \ "description").text must be equalTo ("")
        }
      }

      "offset" in {
        withDb(modules: _*) { implicit injector =>
          val (library) = db.readWrite { implicit s =>
            val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
            val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").saved
            val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
            val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

            val keep1 = KeepFactory.keep().withTitle("Google").withUser(user).withUri(uri1).withNote("Google Note").withLibrary(library).saved
            val keep2 = KeepFactory.keep().withTitle("Amazon").withUser(user).withUri(uri1).withLibrary(library).saved
            (library)
          }
          val commander = inject[FeedCommander]
          val resultTry = Await.ready(commander.libraryFeed(library, keepCountToDisplay = 1, offset = 1), Duration.Inf).value.get
          resultTry.isSuccess must equalTo(true)
          val result = resultTry.get
          (result \ "channel" \ "title").text must contain("test by Colin Lane")

          val items = (result \ "channel" \ "item")
          items.size must equalTo(1)

          val google = items(0)
          (google \ "title").text must be equalTo ("Google")
          (google \ "description").text must equalTo("") // we get our descriptions from rover not from the note.
          (google \ "link").text must equalTo("http://www.google.com/")
        }
      }
    }
  }
}
