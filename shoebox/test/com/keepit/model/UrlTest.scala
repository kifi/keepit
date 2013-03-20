package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test.Helpers._

class URLTest extends Specification {

  "Url" should {

    "load by user and uri" in {
      running(new EmptyApplication()) {
        val repo = inject[URLRepo]
        repo.eq(inject[URLRepo]) === true //verify singleton

        inject[Database].readWrite{ implicit session =>
          repo.count === 0
        }

        val (url1, nuri1, url2, nuri2, nuri3) = inject[Database].readWrite { implicit session =>
          val nuriRepo = inject[NormalizedURIRepo]
          val nuri1 = nuriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
          val nuri2 = nuriRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))
          val nuri3 = nuriRepo.save(NormalizedURIFactory("Typesafe", "http://www.typesafe.com/"))
          val url1 = repo.save(URLFactory("http://www.google.com/#1", nuri1.id.get))
          val url2 = repo.save(URLFactory("http://www.bing.com/#hahabing", nuri2.id.get))

          (url1, nuri1, url2, nuri2, nuri3)
        }

        inject[Database].readOnly{ implicit session =>
          println(repo.all)
          repo.get("http://cnn.com").isDefined === false
          repo.get("http://www.google.com/#1").isDefined === true
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
