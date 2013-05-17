package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.{DbRepos, EmptyApplication}
import play.api.Play.current
import org.specs2.mutable._
import com.keepit.common.time._
import play.api.test.Helpers._


class ScrapeInfoTest extends Specification with DbRepos {

  def setup() = {
    inject[Database].readWrite {implicit s =>

      val uri1 = uriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))
      val uri3 = uriRepo.save(NormalizedURIFactory("Convex Optimization", "http://www.stanford.edu/~boyd/cvxbook/bv_cvxbook.pdf"))
      val uri4 = uriRepo.save(NormalizedURIFactory("Purely Functional Data Structures", "http://www.cs.cmu.edu/~rwh/theses/okasaki.pdf"))
      val uri5 = uriRepo.save(NormalizedURIFactory("Map Reduce", "http://static.usenix.org/event/osdi04/tech/full_papers/dean/dean.pdf"))
      val uri6 = uriRepo.save(NormalizedURIFactory("BBC Feynman Archive", "http://www.bbc.co.uk/archive/feynman/"))
      val uri7 = uriRepo.save(NormalizedURIFactory("Learn French in One Word", "http://www.youtube.com/watch?v=GSeaDQ6sPs0"))

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
      running(new EmptyApplication()) {

        val (uri1, uri2, uri3, uri4, uri5, uri6, uri7, _, _, _, _, _, _, _) = setup()

        inject[Database].readWrite {implicit s =>

          scrapeInfoRepo.setForRescrapeByRegex("%.pdf", 4) === 2
          val deadline = currentDateTime.plusHours(4)

          (scrapeInfoRepo.getByUri(uri1.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUri(uri2.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUri(uri3.id.get).get.nextScrape isBefore deadline) === true
          (scrapeInfoRepo.getByUri(uri4.id.get).get.nextScrape isBefore deadline) === true
          (scrapeInfoRepo.getByUri(uri5.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUri(uri6.id.get).get.nextScrape isBefore deadline) === false
          (scrapeInfoRepo.getByUri(uri7.id.get).get.nextScrape isBefore deadline) === false

          (scrapeInfoRepo.getByUri(uri1.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUri(uri2.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUri(uri3.id.get).get.signature) must beEmpty;
          (scrapeInfoRepo.getByUri(uri4.id.get).get.signature) must beEmpty;
          (scrapeInfoRepo.getByUri(uri5.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUri(uri6.id.get).get.signature) must not beEmpty;
          (scrapeInfoRepo.getByUri(uri7.id.get).get.signature) must not beEmpty;
        }

      }
    }
  }
}
