package com.keepit.scraper

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import scala.util.Random
import com.keepit.model._
import com.keepit.common.db.CX
import play.api.Play.current
import play.api.test.Helpers._
import com.keepit.test.EmptyApplication
import javax.xml.bind.DatatypeConverter._

@RunWith(classOf[JUnitRunner])
class DuplicateDocumentDetectionTest extends SpecificationWithJUnit {

  "Signature" should {

    "find similar documents of different thresholds" in {
      running(new EmptyApplication()) {

        val builder1 = new SignatureBuilder(3)
        val builder2 = new SignatureBuilder(3)
        val builder3 = new SignatureBuilder(3)
        val sig1 = builder1.add("aaa bbb ccc ddd eee  fff ggg hhh iii jjj  kkk lll mmm nnn ooo  ppp qqq rrr sss ttt").build
        val sig2 = builder2.add("aaa bbb ccc ddd eee  fff ggg hhh iii x  kkk lll mmm nnn ooo  ppp qqq rrr sss ttt").build
        val sig3 = builder1.add("uuu").build
        val sig4 = builder3.add("Completely unrelated to the others. In no way similar. These documents aren't even close.").build

        val documentSignatures2 = CX.withConnection { implicit conn =>
          val nuri1 = NormalizedURIFactory("http://google.com/1").save
          val nuri2 = NormalizedURIFactory("http://google.com/2").save
          val nuri3 = NormalizedURIFactory("http://google.com/3").save
          val nuri4 = NormalizedURIFactory("http://google.com/4").save
          val nuri5 = NormalizedURIFactory("http://google.com/5").save

          implicit val conf = com.keepit.scraper.ScraperConfig()

          ScrapeInfoCxRepo.ofUri(nuri1).copy(signature = sig1.toBase64).save
          ScrapeInfoCxRepo.ofUri(nuri2).copy(signature = sig1.toBase64).save
          ScrapeInfoCxRepo.ofUri(nuri3).copy(signature = sig2.toBase64).save
          ScrapeInfoCxRepo.ofUri(nuri4).copy(signature = sig3.toBase64).save
          ScrapeInfoCxRepo.ofUri(nuri5).copy(signature = sig4.toBase64).save

          ScrapeInfoCxRepo.all.map(s => (s.uriId, parseBase64Binary(s.signature)))
        }

        val documentSignatures = CX.withConnection { implicit conn =>
          ScrapeInfoCxRepo.all.map(s => (s.uriId, parseBase64Binary(s.signature)))
        }
        val dupe = new DuplicateDocumentDetection(documentSignatures)

        val res1 = dupe.processDocuments(1.0)
        val res2 = dupe.processDocuments(0.9)
        val res3 = dupe.processDocuments(0.5)

        res1.map(_._1.id) === Seq(1L)
        res1.head._2.size === 1

        res2.size == 2
        res3.size == 3
      }
    }
  }
}

