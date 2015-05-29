package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model._
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

    "filter by count and offset" in {
      withDb(modules: _*) { implicit injector =>
        val (user, library, privateLibrary) = setup
        db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("Google"), userId = user.id.get, url = url1.url, urlId = url1.id.get, note = Some("Google Note"),
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(1),
            visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("Amazon"), userId = user.id.get, url = url2.url, urlId = url2.id.get, note = None,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.PUBLISHED, libraryId = Some(library.id.get), inDisjointLib = library.isDisjoint))
        }

        inject[FakeUserActionsHelper].setUser(user)
        inject[LocalUserExperimentCommander].addExperimentForUser(user.id.get, ExperimentType.LIBRARY_RSS_FEED)
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
