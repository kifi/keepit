package com.keepit.commanders

import java.net.URLEncoder

import com.google.inject.Injector
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.slack.models.SlackChannelName
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class LibrarySubscriptionCommanderTest extends Specification with ShoeboxTestInjector {
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
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
        val subKey = LibrarySubscriptionKey("competitors", SlackInfo("http://www.fakewebhook.com"), disabled = false)

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
        db.readWrite { implicit session =>
          librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "competitors", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook.com/")))
          librarySubscriptionRepo.save(LibrarySubscription(libraryId = library.id.get, name = "competitors2", trigger = SubscriptionTrigger.NEW_KEEP, info = SlackInfo("http://www.fakewebhook2.com/")))
        }

        val subKeys = Seq(LibrarySubscriptionKey("competitors1", SlackInfo("http://www.fakewebhook.com"), disabled = false),
          LibrarySubscriptionKey("competitors3", SlackInfo("http://www.fakewebhook3.com"), disabled = false))

        val (newSubs, didSubsChange) = db.readWrite { implicit s =>
          val oldSubs = librarySubscriptionRepo.getByLibraryId(library.id.get)
          libSubCommander.updateSubsByLibIdAndKey(library.id.get, subKeys)
          val newSubs = librarySubscriptionRepo.getByLibraryId(library.id.get)
          (newSubs, oldSubs != newSubs)
        }

        didSubsChange === true

        newSubs.exists {
          _.name == "competitors"
        } === false
        newSubs.exists {
          _.name == "competitors1"
        } === true
        newSubs.exists {
          _.name == "competitors2"
        } === false
        newSubs.exists {
          _.info == SlackInfo("http://www.fakewebhook.com")
        } === true
        newSubs.exists {
          _.info == SlackInfo("http://www.fakewebhook2.com")
        } === false
        newSubs.exists {
          _.info == SlackInfo("http://www.fakewebhook3.com")
        } === true

      }
    }
    "format new-keep messages properly" in {
      "slack messages" in {
        withDb() { implicit injector =>
          val (user, lib, keep) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val keep = KeepFactory.keep().withUser(user).withLibrary(lib).saved
            (user, lib, keep)
          }
          val message = libSubCommander.slackMessageForNewKeep(user, keep, lib, SlackChannelName("testChannel"))

          val userRedir = URLEncoder.encode(s"""{"t":"us","uid":"${user.externalId.id}"}""", "ascii")
          val libRedir = URLEncoder.encode(s"""{"t":"lv","lid":"${Library.publicId(lib.id.get).id}"}""", "ascii")
          message.text must contain(s"/redir?data=$userRedir")
          message.text must contain(s"/redir?data=$libRedir")
        }
      }
    }
  }
}
