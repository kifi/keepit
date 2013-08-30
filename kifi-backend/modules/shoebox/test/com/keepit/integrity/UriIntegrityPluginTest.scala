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
import com.keepit.scraper.FakeScraperModule
import com.keepit.common.zookeeper.CentralConfig



class UriIntegrityPluginTest extends Specification with ShoeboxApplicationInjector{



  "uri integrity plugin" should {
    "work" in {
      running(new ShoeboxApplication(TestActorSystemModule(), FakeScraperModule())) {
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeInfoRepo = inject[ScrapeInfoRepo]
        val ruleRepo = inject[UriNormalizationRuleRepo]
        val bmRepo = inject[BookmarkRepo]
        val plugin = inject[UriIntegrityPlugin]
        plugin.onStart()

        def setup() = {
          db.readWrite { implicit session =>
            val nuri0 = uriRepo.save(normalizedURIFactory.apply("Google", "http://www.google.com/").withState(NormalizedURIStates.SCRAPED))
            val nuri1 = uriRepo.save(normalizedURIFactory.apply("Google", "http://google.com/"))
            val nuri2 = uriRepo.save(normalizedURIFactory.apply("Bing", "http://www.bing.com/").withState(NormalizedURIStates.SCRAPED))
            val nuri3 = uriRepo.save(normalizedURIFactory.apply("Bing", "http://www.fakebing.com/"))
            
            val url0 = urlRepo.save(URLFactory("http://www.google.com/#1", nuri0.id.get))             // to be redirected to nuri1
            val url1 = urlRepo.save(URLFactory("http://www.bing.com/index", nuri2.id.get))
            val url2 = urlRepo.save(URLFactory("http://www.fakebing.com/index", nuri2.id.get))        // to be splitted, to be pointing to
            
            val user = userRepo.save(User(firstName = "foo", lastName = "bar"))
            
            val hover = BookmarkSource("HOVER_KEEP")
            val bm1 = bmRepo.save(Bookmark(title = Some("google"), userId = user.id.get, url = url0.url, urlId = url0.id,  uriId = nuri0.id.get, source = hover))
            val bm2 = bmRepo.save(Bookmark(title = Some("bing"), userId = user.id.get, url = url1.url, urlId = url1.id, uriId = nuri2.id.get, source = hover))
            val bm3 = bmRepo.save(Bookmark(title = Some("bing"), userId = user.id.get, url = url2.url, urlId = url2.id, uriId = nuri2.id.get, source = hover))

            (Array(nuri0, nuri1, nuri2, nuri3), Array(url0, url1, url2), Array(bm1, bm2, bm3))
          }
        }
        
        
        val (uris, urls, bms) = setup()
        
        // check init status
        db.readOnly { implicit s =>
          uriRepo.getByState(NormalizedURIStates.ACTIVE, -1).size === 2
          uriRepo.getByState(NormalizedURIStates.SCRAPED, -1).size === 2
          
          urlRepo.getByNormUri(uris(0).id.get).head.url === urls(0).url
          urlRepo.getByNormUri(uris(1).id.get) === Nil
          
          urlRepo.getByNormUri(uris(2).id.get).size === 2
          urlRepo.getByNormUri(uris(3).id.get).size === 0
          
          scrapeInfoRepo.getByUri(uris(0).id.get).head.state === ScrapeInfoStates.ACTIVE
          
          bmRepo.getByUrlId(urls(0).id.get).head.uriId === uris(0).id.get
          
        }
        
        // merge
        plugin.handleChangedUri(MergedUri(uris(0).id.get, uris(1).id.get))
        plugin.batchUpdateMerge()
        
        // check redirection
        db.readOnly{ implicit s =>
          uriRepo.getByState(NormalizedURIStates.REDIRECTED, -1).size === 1
          uriRepo.getByState(NormalizedURIStates.SCRAPE_WANTED, -1).size === 1      
          uriRepo.getByState(NormalizedURIStates.SCRAPE_WANTED, -1).head.id === uris(1).id
          urlRepo.getByNormUri(uris(1).id.get).head.url === urls(0).url
          urlRepo.getByNormUri(uris(0).id.get) === Nil
          scrapeInfoRepo.getByUri(uris(0).id.get).head.state === ScrapeInfoStates.INACTIVE
          
          bmRepo.getByUrlId(urls(0).id.get).head.uriId === uris(1).id.get
          bmRepo.getByUrlId(urls(1).id.get).head.uriId === uris(2).id.get
          bmRepo.getByUrlId(urls(2).id.get).head.uriId === uris(2).id.get
          
        }
        
        val centralConfig = inject[CentralConfig]
        centralConfig(new ChangedUriSeqNumKey()) === Some(2)
        
        // split
        plugin.handleChangedUri(SplittedUri(urls(2), uris(3).id.get))
        
        db.readOnly{ implicit s =>
          uriRepo.getByState(NormalizedURIStates.REDIRECTED, -1).size === 1
          uriRepo.getByState(NormalizedURIStates.SCRAPE_WANTED, -1).size === 2
          urlRepo.getByNormUri(uris(2).id.get).head.url === urls(1).url
          urlRepo.getByNormUri(uris(3).id.get).head.url === urls(2).url
          
          bmRepo.getByUrlId(urls(1).id.get).head.uriId === uris(2).id.get
          bmRepo.getByUrlId(urls(2).id.get).head.uriId === uris(3).id.get

        }
        

      }
    }
  }
}
