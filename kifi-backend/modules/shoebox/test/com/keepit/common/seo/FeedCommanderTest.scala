package com.keepit.common.seo

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
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
        val (library) = db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").withEmailAddress("colin@kifi.com").saved
          val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("Google"), userId = user.id.get, url = url1.url, urlId = url1.id.get, note = Some("Google Note"),
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(1),
            visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("Amazon"), userId = user.id.get, url = url2.url, urlId = url2.id.get, note = None,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
          (library)
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
            val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").withEmailAddress("colin@kifi.com").saved
            val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
            val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

            val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
            val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

            val keep1 = keepRepo.save(Keep(title = Some("Google"), userId = user.id.get, url = url1.url, urlId = url1.id.get, note = Some("Google Note"),
              uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(1),
              visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
            val keep2 = keepRepo.save(Keep(title = Some("Amazon"), userId = user.id.get, url = url2.url, urlId = url2.id.get, note = None,
              uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
              visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
            (library)
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
            val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").withEmailAddress("colin@kifi.com").saved
            val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
            val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

            val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
            val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

            val keep1 = keepRepo.save(Keep(title = Some("Google"), userId = user.id.get, url = url1.url, urlId = url1.id.get, note = Some("Google Note"),
              uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(1),
              visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
            val keep2 = keepRepo.save(Keep(title = Some("Amazon"), userId = user.id.get, url = url2.url, urlId = url2.id.get, note = None,
              uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
              visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
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
