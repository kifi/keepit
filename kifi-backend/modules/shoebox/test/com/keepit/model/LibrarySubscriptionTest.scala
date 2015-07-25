package com.keepit.model

import com.google.inject.Injector
import com.keepit.commanders.LibrarySubscriptionCommander
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult

class LibrarySubscriptionTest extends Specification with ShoeboxTestInjector {
  def setup(numLibSubs: Int = 1)(implicit injector: Injector): (User, Library) = {
    db.readWrite { implicit session =>
      val user = userRepo.save(User(firstName = "Link", lastName = "ToThePast", primaryUsername = Some(PrimaryUsername(Username("link"), Username("link")))))
      val library = libraryRepo.save(Library(name = "Hyrule Temple", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("hyrule-temple"), memberCount = 0))
      (user, library)
    }
  }

  "LibrarySubscriptionRepo" should {
    "save and get properly" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val (libSubExpected, libSubActual) = db.readWrite { implicit session =>
          val libSubExpected = librarySubscriptionRepo.save(LibrarySubscription(name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSubActual = librarySubscriptionRepo.getByLibraryId(library.id.get).head
          (libSubExpected, libSubActual)
        }
        libSubExpected === libSubActual
      }
    }

    "get all subscriptions with a specified trigger for a library" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val (libSubExpected, libSubActual) = db.readWrite { implicit session =>
          val libSubExpected = librarySubscriptionRepo.save(LibrarySubscription(name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSubActual = librarySubscriptionRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP).head
          (libSubExpected, libSubActual)
        }
        libSubExpected === libSubActual
      }
    }

    "get only active subscriptions by default" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val (libSubExpected, activeLibSubs) = db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(state = LibrarySubscriptionStates.INACTIVE, name = "fake inactive lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSubExpected = librarySubscriptionRepo.save(LibrarySubscription(name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val activeLibSubs = librarySubscriptionRepo.getByLibraryId(library.id.get)
          (libSubExpected, activeLibSubs)
        }
        activeLibSubs.length === 1
        libSubExpected === activeLibSubs.head
      }
    }

    "get inactive subscriptions when specified" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val (libSubExpected1, libSubExpected2, libSubsActual) = db.readWrite { implicit session =>
          val libSub1 = librarySubscriptionRepo.save(LibrarySubscription(state = LibrarySubscriptionStates.INACTIVE, name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSub2 = librarySubscriptionRepo.save(LibrarySubscription(name = "fake inactive lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSubsActual = librarySubscriptionRepo.getByLibraryId(library.id.get, excludeStates = Set.empty)
          (libSub1, libSub2, libSubsActual)
        }
        libSubsActual.length === 2
        libSubsActual must contain(libSubExpected1)
        libSubsActual must contain(libSubExpected2)
      }
    }

    "get subscriptions by name" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val (libSubExpected, libSubActual) = db.readWrite { implicit session =>
          val libSubExpected = librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "my library sub", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSubActual = librarySubscriptionRepo.getByLibraryIdAndName(libraryId = library.id.get, name = "My Library Sub").get
          (libSubExpected, libSubActual)
        }
        libSubExpected === libSubActual
      }
    }
  }
}
