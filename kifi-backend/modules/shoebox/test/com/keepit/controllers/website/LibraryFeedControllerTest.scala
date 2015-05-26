package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

/**
 * Created by colinlane on 5/22/15.
 */
class LibraryFeedControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxServiceModule()
  )

  "Library Feed Controller" should {
    def setup()(implicit injector: Injector) = db.readWrite { implicit s =>
      val user = userRepo.save(User(createdAt = DateTime.now, firstName = "Colin", lastName = "Lane", username = Username("Colin-Lane"),
        primaryEmail = Some(EmailAddress("colin@kifi.com")), normalizedUsername = "colin-lane"))
      val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))
      val privateLibrary = libraryRepo.save(Library(name = "secret", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = privateLibrary.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
      (user, library, privateLibrary)
    }

    "serve rss for" in {
      "logged in user with access to" in {
        "private library owned by user with experiment" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            inject[FakeUserActionsHelper].setUser(user)
            inject[LocalUserExperimentCommander].addExperimentForUser(user.id.get, ExperimentType.LIBRARY_RSS_FEED)

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]

            val result = libraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value)(request)
            status(result) should equalTo(OK)
          }
        }
        "public library owned by user with experiment" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            inject[FakeUserActionsHelper].unsetUser()
            inject[LocalUserExperimentCommander].addExperimentForUser(user.id.get, ExperimentType.LIBRARY_RSS_FEED)

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, library.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]

            val result = libraryFeedController.libraryRSSFeed(user.username, library.slug.value)(request)
            status(result) should equalTo(OK)
          }
        }
      }
    }

    "not serve rss for" in {
      "logged out user" in {
        "accessing private library" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            inject[FakeUserActionsHelper].unsetUser()

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]
            val result = libraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value)(request)
            status(result) should equalTo(NOT_FOUND)
          }
        }
        "accessing public library owned by user without experiment" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            inject[FakeUserActionsHelper].unsetUser()

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, library.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]
            val result = libraryFeedController.libraryRSSFeed(user.username, library.slug.value)(request)
            status(result) should equalTo(NOT_FOUND)
          }
        }
      }
      "logged in user" in {
        "without access to private library" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            val otherUser = db.readWrite { implicit s =>
              userRepo.save(User(createdAt = DateTime.now, firstName = "Colin", lastName = "Lane", username = Username("Colin-Lane"),
                primaryEmail = Some(EmailAddress("colin@kifi.com")), normalizedUsername = "colin-lane"))
            }
            inject[FakeUserActionsHelper].setUser(otherUser)

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]
            val result = libraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value)(request)
            status(result) should equalTo(NOT_FOUND)
          }
        }
        "with access to private library owned by user without experiment" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            val otherUser = db.readWrite { implicit s =>
              val user1 = userRepo.save(User(createdAt = DateTime.now, firstName = "Colin", lastName = "Lane", username = Username("Colin-Lane"),
                primaryEmail = Some(EmailAddress("colin@kifi.com")), normalizedUsername = "colin-lane"))
              libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY))
              user1
            }
            inject[FakeUserActionsHelper].setUser(otherUser)

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]
            val result = libraryFeedController.libraryRSSFeed(user.username, privateLibrary.slug.value)(request)
            status(result) should equalTo(NOT_FOUND)
          }
        }
      }
    }
  }
}
