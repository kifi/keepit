package com.keepit.common.seo

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
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
      val user = UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").saved
      val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))

      val uri0 = uriRepo.save(NormalizedURI.withHash("http://www.kiiifffiii.com/", Some("Kifi")))
      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

      KeepFactory.keep().withTitle("Kifi").withUri(uri0).withUser(user).withLibrary(library).withKeptAt(t1 plusMinutes 5).saved
      KeepFactory.keep().withTitle("Google").withUri(uri1).withUser(user).withLibrary(library).withKeptAt(t1 plusMinutes 10).saved
      KeepFactory.keep().withTitle("Amazon").withUri(uri2).withUser(user).withLibrary(library).withKeptAt(t1 plusMinutes 15).saved
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

  "Atom Feed Commander" should {
    "create feed for library" in {
      withDb(modules: _*) { implicit injector =>
        val library = setup()
        val commander = inject[AtomCommander]
        val resultTry = Await.ready(commander.libraryFeed(library), Duration.Inf).value.get
        resultTry.isSuccess must equalTo(true)
        val result = resultTry.get
        (result \ "title") === "test by Colin Lane * Kifi"
        (result \ "author" \ "name") === "Colin Lane"
        (result \ "id") contains "urn:kifi:"
        // Library was just created, should be accurate
        new DateTime((result \ "updated").text).getMillis() - DateTime.now().getMillis < 100000
        ((result \ "link")(0) \ "@href") === "http://dev.ezkeep.com:9000/colin-lane/test/atom"
        ((result \ "link")(0) \ "@rel") === "self"

        (result \ "entry")(0) \ "title" === "Amazon"
        (result \ "entry")(1) \ "title" === "Google"
        (result \ "entry")(2) \ "title" === "Kifi"

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
          (result \ "entry").size must equalTo(2)

          (result \ "entry")(0) \ "title" === "Google"
          (result \ "entry")(1) \ "title" === "Kifi"

        }
      }

      "count" in {
        withDb(modules: _*) { implicit injector =>
          val library = setup()
          val commander = inject[AtomCommander]
          val resultTry = Await.ready(commander.libraryFeed(library, keepCountToDisplay = 1, offset = 1), Duration.Inf).value.get
          resultTry.isSuccess must equalTo(true)
          val result = resultTry.get
          (result \ "entry").size must equalTo(1)

          (result \ "entry")(0) \ "title" === "Google"
        }
      }
    }
  }
}
