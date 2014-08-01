package com.keepit.scraper

import org.specs2.mutable._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector
import javax.xml.bind.DatatypeConverter._
import com.google.inject.Injector
import com.keepit.integrity.DuplicateDocumentDetection
import com.keepit.common.mail.FakeMailModule

class DuplicateDocumentDetectionTest extends Specification with ShoeboxTestInjector {

  "Signature" should {

    "find similar documents of different thresholds" in {
      withDb(FakeMailModule(), FakeScrapeSchedulerModule()) { implicit injector: Injector =>

        val builder1 = new FakeSignatureBuilder(3)
        val builder2 = new FakeSignatureBuilder(3)
        val builder3 = new FakeSignatureBuilder(3)
        val sig1 = builder1.add("aaa bbb ccc ddd eee  fff ggg hhh iii jjj  kkk lll mmm nnn ooo  ppp qqq rrr sss ttt").build
        val sig2 = builder2.add("aaa bbb ccc ddd eee  fff ggg hhh iii x  kkk lll mmm nnn ooo  ppp qqq rrr sss ttt").build
        val sig3 = builder1.add("uuu").build
        val sig4 = builder3.add("Completely unrelated to the others. In no way similar. These documents aren't even close.").build

        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        val documentSignatures2 = inject[Database].readWrite { implicit s =>
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://google.com/1"))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://google.com/2"))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://google.com/3"))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://google.com/4"))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://google.com/5"))

          scrapeRepo.save(ScrapeInfo(uriId = nuri1.id.get))
          scrapeRepo.save(ScrapeInfo(uriId = nuri2.id.get))
          scrapeRepo.save(ScrapeInfo(uriId = nuri3.id.get))
          scrapeRepo.save(ScrapeInfo(uriId = nuri4.id.get))
          scrapeRepo.save(ScrapeInfo(uriId = nuri5.id.get))

          scrapeRepo.save(scrapeRepo.getByUriId(nuri1.id.get).get.copy(signature = sig1.toBase64))
          scrapeRepo.save(scrapeRepo.getByUriId(nuri2.id.get).get.copy(signature = sig1.toBase64))
          scrapeRepo.save(scrapeRepo.getByUriId(nuri3.id.get).get.copy(signature = sig2.toBase64))
          scrapeRepo.save(scrapeRepo.getByUriId(nuri4.id.get).get.copy(signature = sig3.toBase64))
          scrapeRepo.save(scrapeRepo.getByUriId(nuri5.id.get).get.copy(signature = sig4.toBase64))

          scrapeRepo.all.map(s => (s.uriId, parseBase64Binary(s.signature)))
        }

        val dupe = inject[DuplicateDocumentDetection]

        val res1 = dupe.findDupeDocuments(1.0)
        val res2 = dupe.findDupeDocuments(0.9)
        val res3 = dupe.findDupeDocuments(0.5)

        res1.map(_._1.id) === Seq(1L)
        res1.head._2.size === 1

        res2.size === 2
        res3.size === 3
      }
    }
  }
}

