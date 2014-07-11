package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable._
import com.keepit.common.time._
import com.google.inject.Injector

class ScrapeInfoTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    inject[Database].readWrite { implicit s =>

      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")))
      val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.stanford.edu/~boyd/cvxbook/bv_cvxbook.pdf", Some("Convex Optimization")))
      val uri4 = uriRepo.save(NormalizedURI.withHash("http://www.cs.cmu.edu/~rwh/theses/okasaki.pdf", Some("Purely Functional Data Structures")))
      val uri5 = uriRepo.save(NormalizedURI.withHash("http://static.usenix.org/event/osdi04/tech/full_papers/dean/dean.pdf", Some("Map Reduce")))
      val uri6 = uriRepo.save(NormalizedURI.withHash("http://www.bbc.co.uk/archive/feynman/", Some("BBC Feynman Archive")))
      val uri7 = uriRepo.save(NormalizedURI.withHash("http://www.youtube.com/watch?v=GSeaDQ6sPs0", Some("Learn French in One Word")))

      val info1 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri1.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.ACTIVE, signature = "nonempty"))
      val info2 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri2.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.ACTIVE, signature = "nonempty"))
      val info3 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri3.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.ACTIVE, signature = "nonempty"))
      val info4 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri4.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.ACTIVE, signature = "nonempty"))
      val info5 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri5.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.INACTIVE, signature = "nonempty"))
      val info6 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri6.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.ACTIVE, signature = "nonempty"))
      val info7 = scrapeInfoRepo.save(ScrapeInfo(uriId = uri7.id.get, nextScrape = END_OF_TIME, state = ScrapeInfoStates.ACTIVE, signature = "nonempty"))

      (uri1, uri2, uri3, uri4, uri5, uri6, uri7, info1, info2, info3, info4, info5, info6, info7)
    }
  }

  "Scrape Info" should {
    "set the next scrape by regex" in {
      withDb() { implicit injector =>

        val (uri1, uri2, uri3, uri4, uri5, uri6, uri7, _, _, _, _, _, _, _) = setup()

        inject[Database].readWrite { implicit s =>

          scrapeInfoRepo.setForRescrapeByRegex("%.pdf", 4) === 2
          val deadline = currentDateTime.plusHours(4)

          (scrapeInfoRepo.getByUriId(uri1.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUriId(uri2.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUriId(uri3.id.get).get.nextScrape isBefore deadline) === true
          (scrapeInfoRepo.getByUriId(uri4.id.get).get.nextScrape isBefore deadline) === true
          (scrapeInfoRepo.getByUriId(uri5.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUriId(uri6.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUriId(uri7.id.get).get.nextScrape isBefore deadline) === false

          (scrapeInfoRepo.getByUriId(uri1.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUriId(uri2.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUriId(uri3.id.get).get.signature) must beEmpty;
          (scrapeInfoRepo.getByUriId(uri4.id.get).get.signature) must beEmpty;
          (scrapeInfoRepo.getByUriId(uri5.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUriId(uri6.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUriId(uri7.id.get).get.signature) must not beEmpty;
        }

      }
    }
  }
}
