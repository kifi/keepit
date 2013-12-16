package com.keepit.commanders

import com.keepit.test._
import play.api.test.Helpers._
import org.specs2.mutable.Specification
import com.keepit.model._
import play.api.libs.json.Json
import com.keepit.common.healthcheck._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.heimdal.HeimdalContext

class BookmarkInternerTest extends Specification with ShoeboxApplicationInjector {

  implicit val context = HeimdalContext.empty

  "BookmarkInterner" should {
    "persist bookmark" in {
      running(new ShoeboxApplication(FakeScrapeSchedulerModule())) {
        val user = db.readWrite { implicit db =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val bookmarks = bookmarkInterner.internBookmarks(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          ), user, Set(), BookmarkSource.email)
        db.readWrite { implicit db =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 1
          bookmarkRepo.get(bookmarks.head.id.get) === bookmarks.head
          bookmarkRepo.all.size === 1
        }
      }
    }

    "persist bookmarks" in {
      running(new ShoeboxApplication(FakeScrapeSchedulerModule())) {
        val user = db.readWrite { implicit db =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val bookmarks = bookmarkInterner.internBookmarks(Json.arr(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          ), Json.obj(
            "url" -> "http://kifi.com",
            "isPrivate" -> false
          )), user, Set(), BookmarkSource.email)
        db.readWrite { implicit db =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 2
          bookmarkRepo.all.size === 2
        }
      }
    }
    "persist bookmarks with one bad url" in {
      running(new ShoeboxApplication(FakeScrapeSchedulerModule())) {
        val user = db.readWrite { implicit db =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val fakeAirbrake = inject[FakeAirbrakeNotifier]
        fakeAirbrake.errorCount() === 0
        val bookmarkInterner = inject[BookmarkInterner]
        val bookmarks = bookmarkInterner.internBookmarks(Json.arr(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          ), Json.obj(
            "url" -> ("http://kifi.com/" + List.fill(300)("this_is_a_very_long_url/").mkString),
            "isPrivate" -> false
          ), Json.obj(
            "url" -> "http://kifi.com",
            "isPrivate" -> true
          )), user, Set(), BookmarkSource.email)
        db.readWrite { implicit db =>
          bookmarks.size === 2
          bookmarkRepo.all.size === 2
        }
        fakeAirbrake.errorCount() === 1
      }
    }
    "reactivate inactive bookmarks for the same url" in {
      running(new ShoeboxApplication(FakeScrapeSchedulerModule())) {
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Greg", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val initialBookmarks = bookmarkInterner.internBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        )), user, Set(), BookmarkSource.keeper)
        initialBookmarks.size === 1
        db.readWrite { implicit s =>
          bookmarkRepo.save(bookmarkRepo.getByUser(user.id.get).head.withActive(false))
        }
        val bookmarks = bookmarkInterner.internBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        )), user, Set(), BookmarkSource.keeper)
        db.readOnly { implicit s =>
          bookmarks.size === 1
          bookmarkRepo.all.size === 1
        }
      }
    }
  }

}
