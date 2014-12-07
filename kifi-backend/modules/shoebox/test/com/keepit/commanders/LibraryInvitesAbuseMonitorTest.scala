package com.keepit.commanders

import com.keepit.model.UserFactoryHelper._

import com.google.inject.Injector
import com.keepit.common.healthcheck.{ FakeAirbrakeNotifier, AirbrakeNotifier }
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class LibraryInvitesAbuseMonitorTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit s =>
      val u1 = UserFactory().save
      val u2 = UserFactory().save
      val lib = libraryRepo.save(Library(name = "Lib", ownerId = u1.id.get, slug = LibrarySlug("lib"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = u1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
      val email = EmailAddress("daron@gmail.com")
      (u1, u2, email, lib)
    }
  }

  "LibraryInvitesAbuseControl" should {

    "check for global abuse not triggered" in {
      withDb() { implicit injector =>
        val airbrake = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
        val monitor = new LibraryInvitesAbuseMonitor(absoluteWarn = 20, absoluteError = 50, libraryInviteRepo = libraryInviteRepo, db = db, airbrake = airbrake)
        val (user1, user2, _, lib) = setup
        monitor.inspect(user1.id.get, user2.id, None, lib.id.get, 10)
        airbrake.errorCount() === 0
      }
    }

    "check for global abuse error triggered for Users" in {
      withDb() { implicit injector =>
        val airbrake = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
        val monitor = new LibraryInvitesAbuseMonitor(absoluteWarn = 20, absoluteError = 50, libraryInviteRepo = libraryInviteRepo, db = db, airbrake = airbrake)
        val (user1, user2, _, lib) = setup
        db.readWrite { implicit s =>
          for (i <- 1 to 41)
            libraryInviteRepo.save(LibraryInvite(libraryId = lib.id.get, inviterId = user1.id.get, userId = user2.id, access = LibraryAccess.READ_ONLY))
        }
        monitor.inspect(user1.id.get, user2.id, None, lib.id.get, 10) must throwA[AbuseMonitorException]
      }
    }

    "check for global abuse warn triggered for Users" in {
      withDb() { implicit injector =>
        val airbrake = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
        val monitor = new LibraryInvitesAbuseMonitor(absoluteWarn = 20, absoluteError = 50, libraryInviteRepo = libraryInviteRepo, db = db, airbrake = airbrake)
        val (user1, user2, _, lib) = setup
        db.readWrite { implicit s =>
          for (i <- 1 to 11)
            libraryInviteRepo.save(LibraryInvite(libraryId = lib.id.get, inviterId = user1.id.get, userId = user2.id, access = LibraryAccess.READ_ONLY))
        }
        airbrake.errorCount() === 0
        monitor.inspect(user1.id.get, user2.id, None, lib.id.get, 10) //should not throw an exception
        airbrake.errorCount() === 1
      }
    }

    "check for global abuse error triggered for NonUsers" in {
      withDb() { implicit injector =>
        val airbrake = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
        val monitor = new LibraryInvitesAbuseMonitor(absoluteWarn = 20, absoluteError = 50, libraryInviteRepo = libraryInviteRepo, db = db, airbrake = airbrake)
        val (user1, _, email, lib) = setup
        db.readWrite { implicit s =>
          for (i <- 1 to 41)
            libraryInviteRepo.save(LibraryInvite(libraryId = lib.id.get, inviterId = user1.id.get, emailAddress = Some(email), access = LibraryAccess.READ_ONLY))
        }
        monitor.inspect(user1.id.get, None, Some(email), lib.id.get, 10) must throwA[AbuseMonitorException]
      }
    }

    "check for global abuse warn triggered for NonUsers" in {
      withDb() { implicit injector =>
        val airbrake = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
        val monitor = new LibraryInvitesAbuseMonitor(absoluteWarn = 20, absoluteError = 50, libraryInviteRepo = libraryInviteRepo, db = db, airbrake = airbrake)
        val (user1, _, email, lib) = setup
        db.readWrite { implicit s =>
          for (i <- 1 to 11)
            libraryInviteRepo.save(LibraryInvite(libraryId = lib.id.get, inviterId = user1.id.get, emailAddress = Some(email), access = LibraryAccess.READ_ONLY))
        }
        airbrake.errorCount() === 0
        monitor.inspect(user1.id.get, None, Some(email), lib.id.get, 10) //should not throw an exception
        airbrake.errorCount() === 1
      }
    }
  }
}
