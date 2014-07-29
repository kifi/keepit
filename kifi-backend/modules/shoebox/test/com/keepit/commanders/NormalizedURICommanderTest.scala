package com.keepit.commanders

import com.google.inject.{ Injector }
import com.keepit.common.db.Id
import com.keepit.model.{ NormalizedURIRepo, Restriction, UrlHash, NormalizedURI }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ShoeboxTestInjector }
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class NormalizedURICommanderTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {

    val uriid1: Id[NormalizedURI] = Id[NormalizedURI](1)
    val uriid2: Id[NormalizedURI] = Id[NormalizedURI](2)
    val uriid3: Id[NormalizedURI] = Id[NormalizedURI](3)
    val uriid4: Id[NormalizedURI] = Id[NormalizedURI](4)
    val uriid5: Id[NormalizedURI] = Id[NormalizedURI](5)

    val uri1: NormalizedURI = NormalizedURI(url = "url1", urlHash = UrlHash("url1"), restriction = Some(Restriction.ADULT))
    val uri2: NormalizedURI = NormalizedURI(url = "url2", urlHash = UrlHash("url2"), restriction = Some(Restriction.ADULT))
    val uri3: NormalizedURI = NormalizedURI(url = "url3", urlHash = UrlHash("url3"))
    val uri4: NormalizedURI = NormalizedURI(url = "url4", urlHash = UrlHash("url4"), restriction = Some(Restriction.ADULT))
    val uri5: NormalizedURI = NormalizedURI(url = "url5", urlHash = UrlHash("url5"), restriction = None)

    val normalizedURIRepo = inject[NormalizedURIRepo]

    val uris = db.readWrite { implicit session =>
      (normalizedURIRepo.save(uri1),
        normalizedURIRepo.save(uri2),
        normalizedURIRepo.save(uri3),
        normalizedURIRepo.save(uri4),
        normalizedURIRepo.save(uri5))
    }

    Seq(uris._1.id.get, uris._2.id.get, uris._3.id.get, uris._4.id.get, uris._5.id.get)
  }

  val modules = Seq(
    FakeShoeboxServiceModule())

  "normalizedURICommander" should {

    "return adult restriction status for uris" in {
      withDb(modules: _*) { implicit injector =>
        val uris = setup()
        val commander = inject[NormalizedURICommander]
        val restrictionStatusOfURIs = commander.getAdultRestrictionOfURIs(uris)

        val result = Await.result(restrictionStatusOfURIs, Duration(10, "seconds"))
        result(0) === true
        result(1) === true
        result(2) === false
        result(3) === true
        result(4) === false
      }
    }

  }
}
