package com.keepit.integrity

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxApplication, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers.running

class URLRenormalizeCommanderTest extends Specification with ShoeboxTestInjector {
  "renormalizer" should {
    "word" in {
      withDb(FakeExecutionContextModule()) { implicit injector =>
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val normalizedURIInterner = inject[NormalizedURIInterner]
        val changedUriRepo = inject[ChangedURIRepo]
        val renormRepo = inject[RenormalizedURLRepo]
        val centralConfig = inject[CentralConfig]
        val mailSender = new SystemAdminMailSender {
          def sendMail(email: ElectronicMail): Unit = {}
        }
        val commander = new URLRenormalizeCommander(db, null, mailSender, uriRepo, normalizedURIInterner, urlRepo, changedUriRepo, renormRepo, centralConfig)

        val (uri0, uri1, url0, url1, url2, url3) = db.readWrite { implicit s =>
          val uri0 = uriRepo.save(NormalizedURI.withHash("http://kifi.com/wrong", Some("kifi")).withState(NormalizedURIStates.SCRAPED)) // trigger 1 migration
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://kifi.com/wrong1", Some("kifi")).withState(NormalizedURIStates.SCRAPED)) // trigger splits

          // all these are pointing to completely wrong uri
          val url0 = urlRepo.save(URLFactory("http://www.kifi.com/correct/", uri0.id.get))
          val url1 = urlRepo.save(URLFactory("http://www.kifi.com/correct1-1/", uri1.id.get))
          val url2 = urlRepo.save(URLFactory("http://www.kifi.com/correct1-2/", uri1.id.get))
          val url3 = urlRepo.save(URLFactory("http://www.kifi.com/correct1-2/index.html", uri1.id.get))
          (uri0, uri1, url0, url1, url2, url3)
        }

        commander.doRenormalize(readOnly = false, clearSeq = false, regex = DomainOrURLRegex(None, None))
        db.readOnlyMaster { implicit s =>
          changedUriRepo.all().size === 1
          val changedUri = changedUriRepo.all().head
          changedUri.oldUriId === uri0.id.get
          changedUri.state === ChangedURIStates.APPLIED

          var uri = uriRepo.getByNormalizedUrl("http://www.kifi.com/correct/")
          (uri != None) === true
          changedUri.newUriId === uri.get.id.get

          uri = uriRepo.getByNormalizedUrl("http://www.kifi.com/correct1-1/")
          (uri != None) === true
          uri = uriRepo.getByNormalizedUrl("http://www.kifi.com/correct1-2/")
          (uri != None) === true

        }

      }

    }
  }
}
