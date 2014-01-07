package com.keepit.integrity

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxApplication
import com.keepit.test.ShoeboxApplicationInjector
import play.api.test.Helpers.running
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.test.ShoeboxTestInjector
import com.google.inject.Injector
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.healthcheck.FakeAirbrakeModule

class OrphanCleanerTest extends Specification with ShoeboxApplicationInjector{

  "OphanCleaner" should {

    "clean up uris by changed uris" in {
      running(new ShoeboxApplication(TestActorSystemModule(), FakeScrapeSchedulerModule(), FakeAirbrakeModule())) {
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val changedUriRepo = inject[ChangedURIRepo]
        val bmRepo = inject[BookmarkRepo]
        val cleaner = inject[OrphanCleaner]

        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "foo", lastName = "bar"))
        }

        val hover = BookmarkSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withState(NormalizedURIStates.SCRAPED))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.SCRAPED))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.ask.com/", Some("Ask")).withState(NormalizedURIStates.ACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withState(NormalizedURIStates.SCRAPED))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://www.lycos.com/", Some("Lycos")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri6 = uriRepo.save(NormalizedURI.withHash("http://www.infoseek.com/", Some("Infoseek")).withState(NormalizedURIStates.ACTIVE))
          val nuri7 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4, nuri5, nuri6, nuri7)
        }
        val urls =  db.readWrite { implicit session =>
          val url0 = urlRepo.save(URLFactory("http://www.google.com/", uris(0).id.get))
          val url1 = urlRepo.save(URLFactory("http://www.bing.com/", uris(1).id.get))
          val url2 = urlRepo.save(URLFactory("http://www.yahooo.com/", uris(2).id.get))
          val url3 = urlRepo.save(URLFactory("http://www.ask.com/", uris(3).id.get))
          val url4 = urlRepo.save(URLFactory("http://www.inktomi.com/", uris(4).id.get))
          val url5 = urlRepo.save(URLFactory("http://www.lycos.com/", uris(5).id.get))
          val url6 = urlRepo.save(URLFactory("http://www.infoseek.com/", uris(6).id.get))
          val url7 = urlRepo.save(URLFactory("http://www.altavista.com/", uris(7).id.get))

          Seq(url0, url1, url2, url3, url4, url5, url6, url7)
        }

        val bms = db.readWrite { implicit session =>
          val bm0 = bmRepo.save(Bookmark(title = Some("google"), userId = user.id.get, url = urls(0).url, urlId = urls(0).id,  uriId = uris(0).id.get, source = hover))
          val bm1 = bmRepo.save(Bookmark(title = Some("bing"), userId = user.id.get, url = urls(1).url, urlId = urls(1).id, uriId = uris(1).id.get, source = hover))
          val bm2 = bmRepo.save(Bookmark(title = Some("yahoo"), userId = user.id.get, url = urls(2).url, urlId = urls(2).id,  uriId = uris(2).id.get, source = hover))
          val bm3 = bmRepo.save(Bookmark(title = Some("ask"), userId = user.id.get, url = urls(3).url, urlId = urls(3).id, uriId = uris(3).id.get, source = hover))

          Seq(bm0, bm1, bm2, bm3)
        }

        // initial state
        cleaner.cleanNormalizedURIsByChangedURIs(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }

        db.readWrite { implicit session =>
          uris.drop(1).map{ uri => changedURIRepo.save(ChangedURI(oldUriId = uri.id.get, newUriId = uris(0).id.get, state=ChangedURIStates.APPLIED)) }
        }
        cleaner.cleanNormalizedURIsByChangedURIs(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }
      }
    }

    "clean up uris by bookmarks" in {
      running(new ShoeboxApplication(TestActorSystemModule(), FakeScrapeSchedulerModule(), FakeAirbrakeModule())) {
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val bmRepo = inject[BookmarkRepo]
        val cleaner = inject[OrphanCleaner]

        val (user, other) = db.readWrite { implicit session =>
          (userRepo.save(User(firstName = "foo", lastName = "bar")), userRepo.save(User(firstName = "foo", lastName = "bar")))
        }

        val hover = BookmarkSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withState(NormalizedURIStates.SCRAPED))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.ACTIVE))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          Seq(nuri0, nuri1, nuri2, nuri3)
        }
        val urls =  db.readWrite { implicit session =>
          val url0 = urlRepo.save(URLFactory("http://www.google.com/", uris(0).id.get))
          val url1 = urlRepo.save(URLFactory("http://www.bing.com/", uris(1).id.get))
          val url2 = urlRepo.save(URLFactory("http://www.yahooo.com/", uris(2).id.get))
          val url3 = urlRepo.save(URLFactory("http://www.altavista.com/", uris(3).id.get))

          Seq(url0, url1, url2, url3)
        }

        var bms = db.readWrite { implicit session =>
          val bm0 = bmRepo.save(Bookmark(title = Some("google"), userId = user.id.get, url = urls(0).url, urlId = urls(0).id,  uriId = uris(0).id.get, source = hover))
          val bm1 = bmRepo.save(Bookmark(title = Some("bing"), userId = user.id.get, url = urls(1).url, urlId = urls(1).id, uriId = uris(1).id.get, source = hover))

          Seq(bm0, bm1)
        }

        // initial states
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
        }

        // test: ACTIVE to SCRAPE_WANTED
        bms ++= db.readWrite { implicit session =>
          Seq(bmRepo.save(Bookmark(title = Some("Yahoo"), userId = user.id.get, url = urls(2).url, urlId = urls(2).id,  uriId = uris(2).id.get, source = hover)))
        }
        db.readOnly{ implicit s =>
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPE_WANTED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
        }

        // test: INACTIVE to SCRAPE_WANTED
        bms ++= db.readWrite { implicit session =>
          Seq(bmRepo.save(Bookmark(title = Some("AltaVista"), userId = user.id.get, url = urls(3).url, urlId = urls(3).id,  uriId = uris(3).id.get, source = hover)))
        }
        db.readOnly{ implicit s =>
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPE_WANTED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.SCRAPE_WANTED
        }

        // test: to ACTIVE
        db.readWrite { implicit session =>
          bms.foreach{ bm => bmRepo.save(bm.copy(state = BookmarkStates.INACTIVE)) }
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
        }
        // test: to ACTIVE
        db.readWrite { implicit session =>
          bms.foreach{ bm => bmRepo.save(bm.copy(state = BookmarkStates.INACTIVE)) }
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
        }

        // test: to ACTIVE (first two uris are kept by other)
        uris.foreach{ nuri =>
          db.readWrite{ implicit s => uriRepo.save(nuri.withState(NormalizedURIStates.SCRAPED)) }
        }
        db.readWrite { implicit session =>
          bms.foreach{ bm => bmRepo.save(bm.copy(state = BookmarkStates.INACTIVE)) }
        }
        val obms = db.readWrite{ implicit s =>
          Seq(
            bmRepo.save(Bookmark(title = Some("google"), userId = other.id.get, url = urls(0).url, urlId = urls(0).id,  uriId = uris(0).id.get, source = hover)),
            bmRepo.save(Bookmark(title = Some("bing"), userId = other.id.get, url = urls(1).url, urlId = urls(1).id, uriId = uris(1).id.get, source = hover))
          )
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
        }

        // test: sequence of changes
        db.readWrite { implicit session =>
          bmRepo.save(bms(0).copy(state = BookmarkStates.ACTIVE))
          bmRepo.save(bms(1).copy(state = BookmarkStates.ACTIVE))
          bmRepo.save(obms(0).copy(state = BookmarkStates.INACTIVE))
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
        }
        db.readWrite { implicit session =>
          bmRepo.save(bms(0).copy(state = BookmarkStates.INACTIVE))
          bmRepo.save(bms(1).copy(state = BookmarkStates.INACTIVE))
          bmRepo.save(obms(0).copy(state = BookmarkStates.ACTIVE))
          bmRepo.save(obms(1).copy(state = BookmarkStates.INACTIVE))
        }
        cleaner.clean(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
        }
      }
    }

    "clean up uris by normalized uris" in {
      running(new ShoeboxApplication(TestActorSystemModule(), FakeScrapeSchedulerModule(), FakeAirbrakeModule())) {
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val bmRepo = inject[BookmarkRepo]
        val cleaner = inject[OrphanCleaner]

        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "foo", lastName = "bar"))
        }

        val hover = BookmarkSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withState(NormalizedURIStates.SCRAPED))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.SCRAPED))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.ask.com/", Some("Ask")).withState(NormalizedURIStates.ACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withState(NormalizedURIStates.SCRAPED))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://www.lycos.com/", Some("Lycos")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri6 = uriRepo.save(NormalizedURI.withHash("http://www.infoseek.com/", Some("Infoseek")).withState(NormalizedURIStates.ACTIVE))
          val nuri7 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4, nuri5, nuri6, nuri7)
        }
        val urls =  db.readWrite { implicit session =>
          val url0 = urlRepo.save(URLFactory("http://www.google.com/", uris(0).id.get))
          val url1 = urlRepo.save(URLFactory("http://www.bing.com/", uris(1).id.get))
          val url2 = urlRepo.save(URLFactory("http://www.yahooo.com/", uris(2).id.get))
          val url3 = urlRepo.save(URLFactory("http://www.ask.com/", uris(3).id.get))
          val url4 = urlRepo.save(URLFactory("http://www.inktomi.com/", uris(4).id.get))
          val url5 = urlRepo.save(URLFactory("http://www.lycos.com/", uris(5).id.get))
          val url6 = urlRepo.save(URLFactory("http://www.infoseek.com/", uris(6).id.get))
          val url7 = urlRepo.save(URLFactory("http://www.altavista.com/", uris(7).id.get))

          Seq(url0, url1, url2, url3, url4, url5, url6, url7)
        }

        val bms = db.readWrite { implicit session =>
          val bm0 = bmRepo.save(Bookmark(title = Some("google"), userId = user.id.get, url = urls(0).url, urlId = urls(0).id,  uriId = uris(0).id.get, source = hover))
          val bm1 = bmRepo.save(Bookmark(title = Some("bing"), userId = user.id.get, url = urls(1).url, urlId = urls(1).id, uriId = uris(1).id.get, source = hover))
          val bm2 = bmRepo.save(Bookmark(title = Some("yahoo"), userId = user.id.get, url = urls(2).url, urlId = urls(2).id,  uriId = uris(2).id.get, source = hover))
          val bm3 = bmRepo.save(Bookmark(title = Some("ask"), userId = user.id.get, url = urls(3).url, urlId = urls(3).id, uriId = uris(3).id.get, source = hover))

          Seq(bm0, bm1, bm2, bm3)
        }

        // initial state
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }

        cleaner.cleanNormalizedURIsByNormalizedURIs(readOnly = false)
        db.readOnly{ implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }
      }
    }
  }
}
