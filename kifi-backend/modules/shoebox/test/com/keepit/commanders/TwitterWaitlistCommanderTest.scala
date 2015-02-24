package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.FakeMailModule
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
        val user1 = db.readWrite { implicit s =>
          user().withName("Captain", "Falcon").withUsername("cfalc").saved
        }

        db.readOnlyMaster { implicit s =>
          userRepo.count === 1
          twitterWaitlistRepo.count === 0
        }

        // add a new entry
        twitterWaitlistCommander.addEntry(user1.id.get, "therealcaptainfalcon").isRight === true
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon").nonEmpty === true
        }

        // add a duplicate entry
        twitterWaitlistCommander.addEntry(user1.id.get, "therealcaptainfalcon").isRight === false
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon").nonEmpty === true
        }

      }
    }

    "edit a user" in {
      withDb(modules: _*) { implicit injector =>
        val twitterWaitlistCommander = inject[TwitterWaitlistCommander]
        val twitterWaitlistRepo = inject[TwitterWaitlistRepo]
        val (user1) = db.readWrite { implicit s =>
          user().withName("Captain", "Falcon").withUsername("cfalc").saved
        }

        twitterWaitlistCommander.addEntry(user1.id.get, "therealcaptainfalcon").isRight === true
        val waitlistEntry = db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon")
        }

        val entryId = waitlistEntry.get.id.get

        // change state
        twitterWaitlistCommander.editEntry(entryId, None, Some(TwitterWaitlistEntryStates.ACCEPTED))
        db.readOnlyMaster { implicit s =>
          val entry = twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon")
          entry.get.state === TwitterWaitlistEntryStates.ACCEPTED
        }

        // change userId & handle
        twitterWaitlistCommander.editEntry(entryId, Some("scar"), None)
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "therealcaptainfalcon").isEmpty === true
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, "scar").isEmpty === false
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
          twitterWaitlistRepo.countActiveEntriesBeforeDateTime(t1.plusMinutes(5)) === 3 // 4 total, 1 inactive
        }
      }
    }

  }
}
