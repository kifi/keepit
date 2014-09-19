package com.keepit.integrity

import com.google.inject.Module
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.db.slick.Database
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OrphanCleanerTest extends Specification with ShoeboxTestInjector {

  val modules: Seq[Module] = Seq(
    FakeScrapeSchedulerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeKeepImportsModule()
  )

  "OphanCleaner" should {

    "clean up uris by changed uris" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val bmRepo = inject[KeepRepo]
        val cleaner = inject[OrphanCleaner]

        val (user, lib1) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "foo", lastName = "bar"))
          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          (user, lib1)
        }

        val hover = KeepSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withState(NormalizedURIStates.SCRAPED))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.SCRAPED))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.ask.com/", Some("Ask")).withState(NormalizedURIStates.ACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withState(NormalizedURIStates.SCRAPED))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://www.lycos.com/", Some("Lycos")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri6 = uriRepo.save(NormalizedURI.withHash("http://www.infoseek.com/", Some("Infoseek")).withState(NormalizedURIStates.ACTIVE))
          val nuri7 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          uriRepo.assignSequenceNumbers(1000)

          scrapeInfoRepo.save(ScrapeInfo(uriId = nuri0.id.get))
          scrapeInfoRepo.save(ScrapeInfo(uriId = nuri1.id.get))

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4, nuri5, nuri6, nuri7)
        }
        val urls = db.readWrite { implicit session =>
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
          val bm0 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = urls(0).url, urlId = urls(0).id.get,
            uriId = uris(0).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm1 = bmRepo.save(Keep(title = Some("bing"), userId = user.id.get, url = urls(1).url, urlId = urls(1).id.get,
            uriId = uris(1).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm2 = bmRepo.save(Keep(title = Some("yahoo"), userId = user.id.get, url = urls(2).url, urlId = urls(2).id.get,
            uriId = uris(2).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm3 = bmRepo.save(Keep(title = Some("ask"), userId = user.id.get, url = urls(3).url, urlId = urls(3).id.get,
            uriId = uris(3).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

          Seq(bm0, bm1, bm2, bm3)
        }

        // initial state
        cleaner.cleanNormalizedURIsByChangedURIs(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get) === None
          scrapeInfoRepo.getByUriId(uris(3).id.get) === None
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
          scrapeInfoRepo.getByUriId(uris(5).id.get) === None
          scrapeInfoRepo.getByUriId(uris(6).id.get) === None
          scrapeInfoRepo.getByUriId(uris(7).id.get) === None
        }

        db.readWrite { implicit session =>
          uris.drop(1).map { uri => changedURIRepo.save(ChangedURI(oldUriId = uri.id.get, newUriId = uris(0).id.get, state = ChangedURIStates.APPLIED)) }
        }
        cleaner.cleanNormalizedURIsByChangedURIs(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
          scrapeInfoRepo.getByUriId(uris(5).id.get) === None
          scrapeInfoRepo.getByUriId(uris(6).id.get) === None
          scrapeInfoRepo.getByUriId(uris(7).id.get) === None
        }
      }
    }

    "clean up uris by bookmarks" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val bmRepo = inject[KeepRepo]
        val cleaner = inject[OrphanCleaner]

        val (user, other, lib1) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "foo", lastName = "bar"))
          val other = userRepo.save(User(firstName = "foo", lastName = "bar"))
          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          (user, other, lib1)
        }

        val hover = KeepSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withState(NormalizedURIStates.SCRAPED))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.ACTIVE))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withState(NormalizedURIStates.REDIRECTED))

          uriRepo.assignSequenceNumbers(1000)

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4)
        }
        val urls = db.readWrite { implicit session =>
          val url0 = urlRepo.save(URLFactory("http://www.google.com/", uris(0).id.get))
          val url1 = urlRepo.save(URLFactory("http://www.bing.com/", uris(1).id.get))
          val url2 = urlRepo.save(URLFactory("http://www.yahooo.com/", uris(2).id.get))
          val url3 = urlRepo.save(URLFactory("http://www.altavista.com/", uris(3).id.get))
          val url4 = urlRepo.save(URLFactory("http://www.inktomi.com/", uris(4).id.get))

          Seq(url0, url1, url2, url3, url4)
        }

        var bms = db.readWrite { implicit session =>
          val bm0 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = urls(0).url, urlId = urls(0).id.get,
            uriId = uris(0).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm1 = bmRepo.save(Keep(title = Some("bing"), userId = user.id.get, url = urls(1).url, urlId = urls(1).id.get,
            uriId = uris(1).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

          Seq(bm0, bm1)
        }

        // initial states
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get) === None
          scrapeInfoRepo.getByUriId(uris(1).id.get) === None
          scrapeInfoRepo.getByUriId(uris(2).id.get) === None
          scrapeInfoRepo.getByUriId(uris(3).id.get) === None
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }

        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get) === None
          scrapeInfoRepo.getByUriId(uris(3).id.get) === None
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }

        // test: ACTIVE to SCRAPE_WANTED
        bms ++= db.readWrite { implicit session =>
          uriRepo.save(uris(0).withState(NormalizedURIStates.SCRAPED))
          uriRepo.save(uris(1).withState(NormalizedURIStates.SCRAPE_FAILED))

          uriRepo.assignSequenceNumbers(1000)

          Seq(bmRepo.save(Keep(title = Some("Yahoo"), userId = user.id.get, url = urls(2).url, urlId = urls(2).id.get,
            uriId = uris(2).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get))))
        }
        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get) === None
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }

        // test: INACTIVE to SCRAPE_WANTED
        bms ++= db.readWrite { implicit session =>
          Seq(bmRepo.save(Keep(title = Some("AltaVista"), userId = user.id.get, url = urls(3).url, urlId = urls(3).id.get,
            uriId = uris(3).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get))))
        }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
        }
        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }

        // test: to ACTIVE
        db.readWrite { implicit session =>
          bms.foreach { bm => bmRepo.save(bm.copy(state = KeepStates.INACTIVE)) }
        }
        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }

        // test: to ACTIVE (first two uris are kept by other)
        val obms = db.readWrite { implicit s =>
          uriRepo.save(uris(0).withState(NormalizedURIStates.SCRAPED))
          uriRepo.save(uris(1).withState(NormalizedURIStates.SCRAPE_FAILED))

          uriRepo.assignSequenceNumbers(1000)

          Seq(
            bmRepo.save(Keep(title = Some("google"), userId = other.id.get, url = urls(0).url, urlId = urls(0).id.get,
              uriId = uris(0).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get))),
            bmRepo.save(Keep(title = Some("bing"), userId = other.id.get, url = urls(1).url,
              urlId = urls(1).id.get, uriId = uris(1).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          )
        }
        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }

        // test: sequence of changes
        db.readWrite { implicit session =>
          bmRepo.save(bms(0).copy(state = KeepStates.ACTIVE))
          bmRepo.save(bms(1).copy(state = KeepStates.ACTIVE))
          bmRepo.save(obms(0).copy(state = KeepStates.INACTIVE))
        }
        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }
        db.readWrite { implicit session =>
          bmRepo.save(bms(0).copy(state = KeepStates.INACTIVE))
          bmRepo.save(bms(1).copy(state = KeepStates.INACTIVE))
          bmRepo.save(obms(0).copy(state = KeepStates.ACTIVE))
          bmRepo.save(obms(1).copy(state = KeepStates.INACTIVE))
        }
        cleaner.clean(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get).get.state === ScrapeInfoStates.INACTIVE
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
        }
      }
    }

    "clean up uris by normalized uris" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val bmRepo = inject[KeepRepo]
        val cleaner = inject[OrphanCleaner]

        val (user, lib1) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "foo", lastName = "bar"))
          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          (user, lib1)
        }

        val hover = KeepSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withState(NormalizedURIStates.SCRAPED))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.SCRAPED))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.ask.com/", Some("Ask")).withState(NormalizedURIStates.ACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withState(NormalizedURIStates.SCRAPED))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://www.lycos.com/", Some("Lycos")).withState(NormalizedURIStates.SCRAPE_FAILED))
          val nuri6 = uriRepo.save(NormalizedURI.withHash("http://www.infoseek.com/", Some("Infoseek")).withState(NormalizedURIStates.ACTIVE))
          val nuri7 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          uriRepo.assignSequenceNumbers(1000)

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4, nuri5, nuri6, nuri7)
        }
        val urls = db.readWrite { implicit session =>
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
          val bm0 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = urls(0).url, urlId = urls(0).id.get,
            uriId = uris(0).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm1 = bmRepo.save(Keep(title = Some("bing"), userId = user.id.get, url = urls(1).url, urlId = urls(1).id.get,
            uriId = uris(1).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm2 = bmRepo.save(Keep(title = Some("yahoo"), userId = user.id.get, url = urls(2).url, urlId = urls(2).id.get,
            uriId = uris(2).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bm3 = bmRepo.save(Keep(title = Some("ask"), userId = user.id.get, url = urls(3).url, urlId = urls(3).id.get,
            uriId = uris(3).id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

          Seq(bm0, bm1, bm2, bm3)
        }

        // initial state
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE

          scrapeInfoRepo.getByUriId(uris(0).id.get) === None
          scrapeInfoRepo.getByUriId(uris(1).id.get) === None
          scrapeInfoRepo.getByUriId(uris(2).id.get) === None
          scrapeInfoRepo.getByUriId(uris(3).id.get) === None
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
          scrapeInfoRepo.getByUriId(uris(5).id.get) === None
          scrapeInfoRepo.getByUriId(uris(6).id.get) === None
          scrapeInfoRepo.getByUriId(uris(7).id.get) === None
        }

        db.readWrite { implicit session =>
          scrapeInfoRepo.save(ScrapeInfo(uriId = uris(0).id.get))
          scrapeInfoRepo.save(ScrapeInfo(uriId = uris(1).id.get))
        }

        cleaner.cleanNormalizedURIsByNormalizedURIs(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.SCRAPED
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.SCRAPE_FAILED
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE

          scrapeInfoRepo.getByUriId(uris(0).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(1).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(2).id.get).get.state === ScrapeInfoStates.ACTIVE
          scrapeInfoRepo.getByUriId(uris(3).id.get) === None
          scrapeInfoRepo.getByUriId(uris(4).id.get) === None
          scrapeInfoRepo.getByUriId(uris(5).id.get) === None
          scrapeInfoRepo.getByUriId(uris(6).id.get) === None
          scrapeInfoRepo.getByUriId(uris(7).id.get) === None
        }
      }
    }
  }
}
