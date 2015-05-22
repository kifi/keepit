package com.keepit.common.seo

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScrapeSchedulerConfigModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

import scala.xml.Elem

/**
 * Created by colinlane on 5/22/15.
 */
class FeedCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxServiceModule()
  )

  "Feed Commander" should {
    "create feed for library" in {
      withDb(modules: _*) { implicit injector =>
        val (library) = db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val user = userRepo.save(User(createdAt = DateTime.now, firstName = "Colin", lastName = "Lane", username = Username("Colin-Lane"),
            primaryEmail = Some(EmailAddress("colin@kifi.com")), normalizedUsername = "colin-lane"))
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
        val result = commander.libraryFeed("dev.ezkeep.com:9000/colin-lane/test/rss", library)
        println(result)
        (result \ "channel" \ "title").text must be equalTo ("test")
        val google = (result \ "channel" \ "item")(0)
        (google \ "title").text must be equalTo ("Google")
        (google \ "description").text must equalTo("Google Note")
        (google \ "link").text must equalTo("http://www.google.com/")

        val amazon = (result \ "channel" \ "item")(1)
        (amazon \ "title").text must be equalTo ("Amazon")
        (amazon \ "description").text must be equalTo ("None")
      }
    }
  }
}
