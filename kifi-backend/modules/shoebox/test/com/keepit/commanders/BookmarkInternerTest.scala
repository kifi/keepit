package com.keepit.commanders

import com.keepit.test._
import play.api.test.Helpers._
import org.specs2.mutable.Specification
import com.keepit.model._
import play.api.libs.json.Json
import com.keepit.common.healthcheck._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.heimdal.HeimdalContext

class BookmarkInternerTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty

  def modules = FakeScrapeSchedulerModule() :: Nil

  "BookmarkInterner" should {

    "persist bookmark" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val bookmarks = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmark(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          )), user, Set(), BookmarkSource.email, true)
        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 1
          bookmarkRepo.get(bookmarks.head.id.get).copy(updatedAt = bookmarks.head.updatedAt) === bookmarks.head
          bookmarkRepo.all.size === 1
        }
      }
    }

    "persist bookmarks" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), Json.obj(
          "url" -> "http://kifi.com",
          "isPrivate" -> false
        )))
        val deduped = bookmarkInterner.deDuplicate(raw)
        deduped === raw
        deduped.size === 2
        val bookmarks = bookmarkInterner.internRawBookmarks(raw, user, Set(), BookmarkSource.email, true)

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 2
          bookmarkRepo.all.size === 2
        }
      }
    }
    "dedup on" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[BookmarkInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> false
        )))
        bookmarkInterner.deDuplicate(raw).size === 1
      }
    }
    "dedup off" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[BookmarkInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://43go.com",
          "isPrivate" -> true
        ), Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> false
        )))
        val deduped: Seq[RawBookmarkRepresentation] = bookmarkInterner.deDuplicate(raw)
        deduped.size === 2
      }
    }
    "persist bookmarks with one bad url" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val fakeAirbrake = inject[FakeAirbrakeNotifier]
        fakeAirbrake.errorCount() === 0
        val bookmarkInterner = inject[BookmarkInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), Json.obj(
          "url" -> ("http://kifi.com/" + List.fill(300)("this_is_a_very_long_url/").mkString),
          "isPrivate" -> false
        ), Json.obj(
          "url" -> "http://kifi.com",
          "isPrivate" -> true
        )))
        val deduped = bookmarkInterner.deDuplicate(raw)
        raw === deduped

        val bookmarks = bookmarkInterner.internRawBookmarks(raw, user, Set(), BookmarkSource.email, true)
        println("airbrake errors:")
        println(fakeAirbrake.errors mkString "\n")
        fakeAirbrake.errorCount() === 1
        bookmarks.size === 2
        db.readWrite { implicit session =>
          bookmarkRepo.all.size === 2
        }
      }
    }
    "reactivate inactive bookmarks for the same url" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Greg", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val initialBookmarks = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user, Set(), BookmarkSource.keeper, true)
        initialBookmarks.size === 1
        db.readWrite { implicit s =>
          bookmarkRepo.save(bookmarkRepo.getByUser(user.id.get).head.withActive(false))
        }
        val bookmarks = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user, Set(), BookmarkSource.keeper, true)
        db.readOnly { implicit s =>
          bookmarks.size === 1
          bookmarkRepo.all.size === 1
        }
      }
    }
  }

}
