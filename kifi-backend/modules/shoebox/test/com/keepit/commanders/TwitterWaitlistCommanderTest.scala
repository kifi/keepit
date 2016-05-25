package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.{ EmailAddress, ElectronicMailRepo, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule }
import com.keepit.model.{ TwitterWaitlistEntry, TwitterWaitlistEntryStates, TwitterWaitlistRepo }
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.twitter.TwitterHandle
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import scala.concurrent._
import scala.concurrent.duration.Duration

class TwitterWaitlistCommanderTest extends TestKitSupport with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule(),
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
          val user1 = user().withName("Captain", "Falcon").withUsername("cfalc").saved
          userEmailAddressCommander.intern(user1.id.get, EmailAddress("cfalc@nintendo.com")).get
          user1
        }

        db.readOnlyMaster { implicit s =>
          userRepo.count === 1
          twitterWaitlistRepo.count === 0
          emailRepo.count === 0
        }

        val therealcaptainfalcon = TwitterHandle("therealcaptainfalcon")

        // add a new entry
        val res1 = twitterWaitlistCommander.addUserToWaitlist(user1.id.get, Some(therealcaptainfalcon))
        res1.isRight === true
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, therealcaptainfalcon).nonEmpty === true
        }

        // add a duplicate entry
        twitterWaitlistCommander.addUserToWaitlist(user1.id.get, Some(therealcaptainfalcon)).isRight === false
        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.getByUserAndHandle(user1.id.get, therealcaptainfalcon).nonEmpty === true
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
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = Some(TwitterHandle("handle1")), state = TwitterWaitlistEntryStates.ACTIVE, createdAt = t1))
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = Some(TwitterHandle("handle2")), state = TwitterWaitlistEntryStates.ACTIVE, createdAt = t1))
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = Some(TwitterHandle("handle3")), state = TwitterWaitlistEntryStates.ACCEPTED, createdAt = t1))
          twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = user1.id.get, twitterHandle = Some(TwitterHandle("handle4")), state = TwitterWaitlistEntryStates.INACTIVE, createdAt = t1))
        }

        db.readOnlyMaster { implicit s =>
          twitterWaitlistRepo.countActiveEntriesBeforeDateTime(t1.plusMinutes(5)) === 2 // 4 total, 1 accepted, 1 inactive
        }
      }
    }

  }
}
