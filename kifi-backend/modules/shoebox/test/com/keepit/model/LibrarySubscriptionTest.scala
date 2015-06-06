package com.keepit.model

import com.google.inject.Injector
import com.keepit.commanders.LibrarySubscriptionCommander
import com.keepit.common.concurrent.FakeExecutionContextModule
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
        val libSubExpected = db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
        }
        val libSubActual: LibrarySubscription = db.readOnlyReplica { implicit session =>
          librarySubscriptionRepo.getByLibraryId(library.id.get).head
        }
        libSubExpected === libSubActual
      }
    }

    "get all subscriptions with a specified trigger for a library" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubExpected = db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
        }
        val libSubActual = db.readOnlyMaster { implicit session =>
          librarySubscriptionRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP)
        }.head
        libSubExpected === libSubActual
      }
    }

    "get only active subscriptions by default" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubExpected: LibrarySubscription = db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(state = LibrarySubscriptionStates.INACTIVE, name = "fake inactive lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          librarySubscriptionRepo.save(LibrarySubscription(name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
        }
        val activeLibSubs = db.readOnlyMaster { implicit session =>
          librarySubscriptionRepo.getByLibraryId(library.id.get)
        }
        activeLibSubs.length === 1
        libSubExpected === activeLibSubs.head
      }
    }

    "get inactive subscriptions when specified" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val (libSubExpected1, libSubExpected2) = db.readWrite { implicit session =>
          val libSub1 = librarySubscriptionRepo.save(LibrarySubscription(state = LibrarySubscriptionStates.INACTIVE, name = "fake lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          val libSub2 = librarySubscriptionRepo.save(LibrarySubscription(name = "fake inactive lib sub", trigger = SubscriptionTrigger.NEW_KEEP, libraryId = library.id.get, info = SlackInfo("http://www.fakewebhook.com/")))
          (libSub1, libSub2)
        }
        val libSubsActual: Seq[LibrarySubscription] = db.readOnlyMaster { implicit session =>
          librarySubscriptionRepo.getByLibraryId(library.id.get, excludeStates = Set.empty)
        }
        libSubsActual.length === 2
        libSubsActual must contain(libSubExpected1)
        libSubsActual must contain(libSubExpected2)
      }
    }

    "get subscriptions by name" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubExpected = db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "my library sub", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook.com/")))
        }
        val libSubActual = db.readOnlyMaster { implicit session =>
          librarySubscriptionRepo.getByLibraryIdAndName(libraryId = library.id.get, name = "My Library Sub").get
        }
        libSubExpected === libSubActual
      }
    }
  }

  "LibrarySubscriptionCommander" should {
    "add a new subscription" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubCommander = inject[LibrarySubscriptionCommander]
        val newSub = db.readWrite { implicit session =>
          libSubCommander.saveSubscription(LibrarySubscription(libraryId = library.id.get, name = "my library sub", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook.com/")))
        }

        db.readOnlyMaster { implicit s => librarySubscriptionRepo.get(newSub.id.get) }.equals(newSub) === true
      }
    }
  }
}
