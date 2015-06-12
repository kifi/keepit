package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class LibrarySubscriptionCommanderTest extends Specification with ShoeboxTestInjector {
  def setup(numLibSubs: Int = 1)(implicit injector: Injector): (User, Library) = {
    db.readWrite { implicit session =>
      val user = userRepo.save(User(firstName = "Link", lastName = "ToThePast", primaryUsername = Some(PrimaryUsername(Username("link"), Username("link")))))
      val library = libraryRepo.save(Library(name = "Hyrule Temple", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("hyrule-temple"), memberCount = 0))
      (user, library)
    }
  }

  "LibrarySubscriptionCommander" should {
    "save new subscription" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubCommander = inject[LibrarySubscriptionCommander]
        val (expectedSub, actualSub) = db.readWrite { implicit session =>
          val expectedSub = librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "my library sub", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook.com/")))
          val actualSub = librarySubscriptionRepo.get(expectedSub.id.get)
          (expectedSub, actualSub)
        }
        expectedSub === actualSub
      }
    }

    "save subscription given a subscription key" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubCommander = inject[LibrarySubscriptionCommander]
        val subKey = LibrarySubscriptionKey("competitors", SlackInfo("http://www.fakewebhook.com"))

        val newSub = db.readWrite { implicit session =>
          libSubCommander.saveSubByLibIdAndKey(library.id.get, subKey)
        }

        newSub.name === "competitors"
        newSub.id.get.id === 1
      }
    }

    "update subscriptions" in {
      withDb() { implicit injector =>
        val (user, library) = setup()
        val libSubCommander = inject[LibrarySubscriptionCommander]
        db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "competitors", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook.com/")))
          librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "competitors2", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook2.com/")))
        }

        val subKeys = Seq(LibrarySubscriptionKey("competitors1", SlackInfo("http://www.fakewebhook.com")),
          LibrarySubscriptionKey("competitors3", SlackInfo("http://www.fakewebhook3.com")))

        val (newSubs, didSubsChange) = db.readWrite { implicit s =>
          val didSubsChange = libSubCommander.updateSubsByLibIdAndKey(library.id.get, subKeys)
          val newSubs = librarySubscriptionRepo.getByLibraryId(library.id.get)
          (newSubs, didSubsChange)
        }

        didSubsChange === true

        newSubs.exists { _.name == "competitors" } === false
        newSubs.exists { _.name == "competitors1" } === true
        newSubs.exists { _.name == "competitors2" } === false
        newSubs.exists { _.info == SlackInfo("http://www.fakewebhook.com") } === true
        newSubs.exists { _.info == SlackInfo("http://www.fakewebhook2.com") } === false
        newSubs.exists { _.info == SlackInfo("http://www.fakewebhook3.com") } === true

      }
    }

  }
}
