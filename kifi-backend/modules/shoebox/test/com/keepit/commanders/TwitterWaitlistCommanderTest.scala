package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.{ ElectronicMailRepo, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule }
import com.keepit.model.{ TwitterWaitlistEntry, TwitterWaitlistEntryStates, TwitterWaitlistRepo }
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import scala.concurrent._
import scala.concurrent.duration.Duration

class TwitterWaitlistCommanderTest extends TestKitSupport with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeScrapeSchedulerModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "TwitterWaitlistCommander" should {

    "add a user" in {
      withDb(modules: _*) { implicit injector =>
        val twitterWaitlistCommander = inject[TwitterWaitlistCommander]
        val twitterWaitlistRepo = inject[TwitterWaitlistRepo]
        val emailRepo = inject[ElectronicMailRepo]
        val user1 = db.readWrite { implicit s =>
          user().withName("Captain", "Falcon").withUsername("cfalc").withEmailAddress("cfalc@nintendo.com").saved
        }

        db.readOnlyMaster { implicit s =>
          userRepo.count === 1
          twitterWaitlistRepo.count === 0
          emailRepo.count === 0
        }

        // add a new entry
        val res1 = twitterWaitlistCommander.addEntry(user1.id.get, "therealcaptainfalcon")
        res1.isRight === true
        val emailFuture = res1.right.get._2.get
        Await.result(emailFuture, Duration(10, "seconds"))
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon").nonEmpty === true
          emailRepo.count === 1
        }

        // add a duplicate entry
        twitterWaitlistCommander.addEntry(user1.id.get, "therealcaptainfalcon").isRight === false
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon").nonEmpty === true
          emailRepo.count === 1
        }
      }
    }

    "count entries so far" in {
      withDb(modules: _*) { implicit injector =>
        val twitterWaitlistRepo = inject[TwitterWaitlistRepo]
        val (user1) = db.readWrite { implicit s =>
          user().withName("Captain", "Falcon").withUsername("cfalc").saved
        }
        val t1 = new DateTime(2014, 8, 1, 7, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
        db.readWrite { implicit s =>
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = "handle1", state = TwitterWaitlistEntryStates.ACTIVE, createdAt = t1))
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = "handle2", state = TwitterWaitlistEntryStates.ACTIVE, createdAt = t1))
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = "handle3", state = TwitterWaitlistEntryStates.ACCEPTED, createdAt = t1))
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = "handle4", state = TwitterWaitlistEntryStates.INACTIVE, createdAt = t1))
        }

        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.countActiveEntriesBeforeDateTime(t1.plusMinutes(5)) === 2 // 4 total, 1 accepted, 1 inactive
        }
      }
    }

  }
}
