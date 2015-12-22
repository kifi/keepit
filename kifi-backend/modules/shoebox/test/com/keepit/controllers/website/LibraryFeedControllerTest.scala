package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class LibraryFeedControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxServiceModule()
  )

  "Library Feed Controller" should {
    def setup()(implicit injector: Injector) = db.readWrite { implicit s =>
      val user = {
        val saved = UserFactory.user().withName("Colin", "Lane").saved
        handleCommander.setUsername(saved, Username("colin-lane")).get
      }
      val library = libraryRepo.save(Library(name = "test", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("test"), memberCount = 1))
      val privateLibrary = libraryRepo.save(Library(name = "secret", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = privateLibrary.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
      (user, library, privateLibrary)
    }

    "filter by count and offset" in {
      withDb(modules: _*) { implicit injector =>
        val (user, library, privateLibrary) = setup
        db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = KeepFactory.keep().withTitle("Google").withUser(user).withLibrary(library).saved
          val keep2 = KeepFactory.keep().withTitle("Amazon").withUser(user).withLibrary(library).saved
        }

        inject[FakeUserActionsHelper].setUser(user)
        val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, library.slug.value, count = 1, offset = 1).url
        val request = FakeRequest("GET", rssPath)
        val libraryFeedController = inject[LibraryFeedController]

        val result = libraryFeedController.libraryRSSFeed(user.username, library.slug.value)(request)
        // Since Amazon was added After Google, it should appear first. We set the offset to 1 and count to 1 so only Google should show.
        contentAsString(result) must contain("<title>Google</title>")
      }
    }

    "serve rss for" in {
      "logged in user with access to" in {
        "public library owned by user with experiment" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            inject[FakeUserActionsHelper].unsetUser()

            val rssPath = com.keepit.controllers.website.routes.LibraryFeedController.libraryRSSFeed(user.username, library.slug.value).url
            val request = FakeRequest("GET", rssPath)
            val libraryFeedController = inject[LibraryFeedController]

            val result = libraryFeedController.libraryRSSFeed(user.username, library.slug.value)(request)
            status(result) should equalTo(OK)
          }
        }
      }

      "logged out user" in {
        "accessing public library owned by user without experiment" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            inject[FakeUserActionsHelper].unsetUser()

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
      }
      "logged in user" in {
        "without access to private library" in {
          withDb(modules: _*) { implicit injector =>
            val (user, library, privateLibrary) = setup
            val otherUser = db.readWrite { implicit s =>
              UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").saved
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
      "private library" in {
        withDb(modules: _*) { implicit injector =>
          val (user, library, privateLibrary) = setup
          inject[FakeUserActionsHelper].setUser(user)

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
