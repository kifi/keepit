package com.keepit.model

import org.specs2.mutable._
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector

class UrlTest extends Specification with ShoeboxTestInjector {

  "Url" should {

    "load by user and uri" in {
      withDb() { implicit injector =>
        val repo = inject[URLRepo]
        repo.eq(inject[URLRepo]) === true //verify singleton

        inject[Database].readWrite { implicit session =>
          repo.count === 0
        }

        val (url1, nuri1, url2, nuri2, nuri3, nuri4) = inject[Database].readWrite { implicit session =>
          val nuriRepo = inject[NormalizedURIRepo]
          val nuri1 = nuriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val nuri2 = nuriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")))
          val nuri3 = nuriRepo.save(NormalizedURI.withHash("http://www.typesafe.com/", Some("Typesafe")))
          val nuri4 = nuriRepo.save(NormalizedURI.withHash("http://cnn.com", Some("CNN")))
          val url1 = repo.save(URLFactory("http://www.google.com/#1", nuri1.id.get))
          val url2 = repo.save(URLFactory("http://www.bing.com/#hahabing", nuri2.id.get))

          (url1, nuri1, url2, nuri2, nuri3, nuri4)
        }

        inject[Database].readOnlyMaster { implicit session =>
          println(repo.all)
          repo.get("http://cnn.com", nuri4.id.get).isDefined === false
          repo.get("http://www.google.com/#1", nuri1.id.get).isDefined === true
          repo.getByNormUri(nuri3.id.get).size === 0
          repo.getByNormUri(nuri2.id.get).head.url === "http://www.bing.com/#hahabing"
        }

      }
    }
    "correctly parse domains when using the factory" in {
      val uri = URLFactory("https://mail.google.com/mail/u/1/", Id[NormalizedURI](42))
      uri.domain === Some("mail.google.com")
    }
  }
}
