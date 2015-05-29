package com.keepit.common.seo

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.xml.NodeSeq

class AtomCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxServiceModule()
  )

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit s =>
      val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val user = userRepo.save(User(createdAt = DateTime.now, firstName = "Colin", lastName = "Lane", username = Username("Colin-Lane"),
        primaryEmail = Some(EmailAddress("colin@kifi.com")), normalizedUsername = "colin-lane"))
      val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

      val uri0 = uriRepo.save(NormalizedURI.withHash("http://www.kiiifffiii.com/", Some("Kifi")))
      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

      val url0 = urlRepo.save(URLFactory(url = uri0.url, normalizedUriId = uri0.id.get))
      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      val keep1 = keepRepo.save(Keep(title = Some("Google"), userId = user.id.get, url = url1.url, urlId = url1.id.get, note = Some("Google Note"),
        uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(2),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
      val keep2 = keepRepo.save(Keep(title = Some("Amazon"), userId = user.id.get, url = url2.url, urlId = url2.id.get, note = None,
        uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
      val keep0 = keepRepo.save(Keep(title = Some("Kifi"), userId = user.id.get, url = url0.url, urlId = url0.id.get, note = Some("Kifiii!"),
        uriId = uri0.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(1),
        visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
      library
    }
  }

  implicit class NodeMatchers(node: NodeSeq) {
    def ===(string: String) = {
      node.text must equalTo(string)
    }
    def contains(string: String) = {
      node.text must contain(string)
    }
  }

  "Feed Commander" should {
    "create feed for library" in {
      withDb(modules: _*) { implicit injector =>
        val library = setup()
        val commander = inject[AtomCommander]
        val resultTry = Await.ready(commander.libraryFeed(library), Duration.Inf).value.get
        resultTry.isSuccess must equalTo(true)
        val result = resultTry.get
        (result \ "title") === "test by Colin-Lane * Kifi"
        (result \ "author" \ "name") === "Colin-Lane"
        (result \ "id") contains "urn:kifi:"
        // Library was just created, should be accurate
        new DateTime((result \ "updated").text).getMillis() - DateTime.now().getMillis < 100000
        ((result \ "link")(0) \ "@href") === "http://dev.ezkeep.com:9000/Colin-Lane/test/atom"
        ((result \ "link")(0) \ "@rel") === "self"

        val kifi = (result \ "entry")(0)
        kifi \ "title" === "Kifi"

        val amazon = (result \ "entry")(1)
        amazon \ "title" === "Amazon"

        val google = (result \ "entry")(2)
        google \ "title" === "Google"
      }
    }

    "limit results with" in {
      "offset" in {
        withDb(modules: _*) { implicit injector =>
          val library = setup()
          val commander = inject[AtomCommander]
          val resultTry = Await.ready(commander.libraryFeed(library, offset = 1), Duration.Inf).value.get
          resultTry.isSuccess must equalTo(true)
          val result = resultTry.get
          // Offset is 1, we aren't showing Kifi
          (result \ "entry").size must equalTo(2)

          val amazon = (result \ "entry")(0)
          amazon \ "title" === "Amazon"

          val google = (result \ "entry")(1)
          google \ "title" === "Google"
        }
      }

      "count" in {
        withDb(modules: _*) { implicit injector =>
          val library = setup()
          val commander = inject[AtomCommander]
          val resultTry = Await.ready(commander.libraryFeed(library, keepCountToDisplay = 1, offset = 1), Duration.Inf).value.get
          resultTry.isSuccess must equalTo(true)
          val result = resultTry.get
          // Results would be "Kifi", "Amazon", "Google", but offset 1 and count 1 make only Amazon show.
          (result \ "entry").size must equalTo(1)

          val amazon = (result \ "entry")(0)
          amazon \ "title" === "Amazon"
        }
      }
    }
  }
}
