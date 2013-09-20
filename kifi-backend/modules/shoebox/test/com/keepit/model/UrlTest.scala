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

        inject[Database].readWrite{ implicit session =>
          repo.count === 0
        }

        val (url1, nuri1, url2, nuri2, nuri3) = inject[Database].readWrite { implicit session =>
          val nuriRepo = inject[NormalizedURIRepo]
          val nuri1 = nuriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val nuri2 = nuriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")))
          val nuri3 = nuriRepo.save(NormalizedURI.withHash("http://www.typesafe.com/", Some("Typesafe")))
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
    
    "correctly retrieve renormalization list" in {
      withDb() { implicit injector =>
        val repo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        inject[Database].readWrite{ implicit s =>
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          (1 to 5).foreach{ i =>
            val url = "www.google.com/" + i
            repo.save(URL(url = url, domain = Some("google"), normalizedUriId = uri1.id.get))
          } 
          
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")))
          (6 to 10).foreach{ i =>
            val url = "www.bing.com/" + i
            repo.save(URL(url = url, domain = Some("bing"), normalizedUriId = uri2.id.get))
          }
          
          val uri3 = uriRepo.save(NormalizedURI.withHash("http://42go.com"))
          (11 to 15).foreach{ i =>
            val url = "42go.com/" + i
            repo.save(URL(url = url, domain = None, normalizedUriId = uri3.id.get))
          }
          
          repo.getRenormalizationList(Id[URL](0), fetchSize = 100).size === 15
          repo.getRenormalizationList(Id[URL](0), domain = Some("google"), fetchSize = 100).size === 5
          repo.getRenormalizationList(Id[URL](0), domain = Some("bing"), fetchSize = 10).foreach{ url =>
            repo.save(url.copy(renormalizationCheck = Some(true)))
          }
          repo.getRenormalizationList(Id[URL](0), fetchSize = 100).size === 10
          repo.getRenormalizationList(Id[URL](0), domain = Some("bing"), fetchSize = 100).size === 0
          repo.getLastRenormalizationId() === Some(Id[URL](10))
          repo.getLastRenormalizationId(domain = Some("bing")) === Some(Id[URL](10))
          repo.getLastRenormalizationId(domain = Some("google")) === None
          repo.getRenormalizationList(Id[URL](2), domain = Some("google"), fetchSize = 100).size === 3
        }
      }
    }
  }
}
