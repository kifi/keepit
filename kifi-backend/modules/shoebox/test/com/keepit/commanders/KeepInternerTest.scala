package com.keepit.commanders

import com.keepit.test._
import play.api.test.Helpers._
import org.specs2.mutable.Specification
import com.keepit.model._
import play.api.libs.json.Json
import com.keepit.common.healthcheck._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.shoebox.{FakeKeepImportsModule, KeepImportsModule}
import com.keepit.common.actor.{StandaloneTestActorSystemModule, TestActorSystemModule}
import akka.actor.ActorSystem
import com.keepit.search.ArticleSearchResult
import com.keepit.common.db.ExternalId
import org.joda.time.DateTime
import com.keepit.common.time._

class KeepInternerTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty
  implicit val system = ActorSystem("test")

  def modules = FakeKeepImportsModule() :: FakeScrapeSchedulerModule() :: Nil

  "BookmarkInterner" should {

    "persist bookmark" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[KeepInterner]
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmark(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          )), user.id.get, KeepSource.email, true)
        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 1
          keepRepo.get(bookmarks.head.id.get).copy(updatedAt = bookmarks.head.updatedAt) === bookmarks.head
          keepRepo.all.size === 1
        }
      }
    }

    "persist to RawKeepRepo" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[KeepInterner]
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.bookmarkImport, Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        )))
        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          rawKeepRepo.all.headOption.map(_.url) === Some("http://42go.com")
          rawKeepRepo.all.size === 1
        }
      }
    }

    "persist bookmarks" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[KeepInterner]
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
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(raw, user.id.get, KeepSource.email, true)

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 2
          keepRepo.all.size === 2
        }
      }
    }

    "tracking clicks & rekeeps" in {
      withDb(modules: _*) { implicit injector =>
        val (u1, u2) = db.readWrite { implicit session =>
          val u1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
          val u2 = userRepo.save(User(firstName = "Clicker", lastName = "Foo"))
          (u1, u2)
        }
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> false
        ), Json.obj(
          "url" -> "http://kifi.com",
          "isPrivate" -> false
        )))
        val deduped = bookmarkInterner.deDuplicate(raw)
        deduped === raw
        deduped.size === 2
        val (keeps1, _) = bookmarkInterner.internRawBookmarks(raw, u1.id.get, KeepSource.email, true)

        val (old, kc) = db.readWrite { implicit rw =>
          val old = keepClickRepo.save(KeepClick(createdAt = currentDateTime.minusMinutes(10), searchUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId, clickerId = u2.id.get))
          val kc = keepClickRepo.save(KeepClick(createdAt = currentDateTime, searchUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId, clickerId = u2.id.get))
          (old, kc)
        }

        val (keeps2, _) = bookmarkInterner.internRawBookmarks(raw, u2.id.get, KeepSource.default, true)

        db.readWrite { implicit session =>
          userRepo.get(u1.id.get) === u1
          keeps1.size === 2
          keepRepo.all.size === 4
          keeps2.size === 2

          val clicks = keepClickRepo.all()
          clicks.size === 2

          val mostRecentOpt = keepClickRepo.getMostRecentClickByClickerAndKeepId(u2.id.get, kc.keepId)
          val click = mostRecentOpt.get
          click.createdAt !== old.createdAt
          click.createdAt === kc.createdAt
          click.searchUUID === kc.searchUUID
          click.keeperId === u1.id.get
          click.keepId === keeps1(1).id.get
          click.clickerId === u2.id.get
          val rekeeps = rekeepRepo.all
          rekeeps.size === 1
          val rekeep = rekeeps(0)
          rekeep.keeperId === u1.id.get
          rekeep.keepId === keeps1(1).id.get
          rekeep.srcUserId === u2.id.get
          rekeep.srcKeepId === keeps2(1).id.get
        }
      }
    }

    "dedup on" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[KeepInterner]
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
        val bookmarkInterner = inject[KeepInterner]
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
        val bookmarkInterner = inject[KeepInterner]
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

        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(raw, user.id.get, KeepSource.email, true)
        fakeAirbrake.errorCount() === 0
        bookmarks.size === 3
        db.readWrite { implicit session =>
          keepRepo.all.size === 3
          keepRepo.all.map(_.url).toSet === Set[String](
            "http://42go.com",
            ("http://kifi.com/" + List.fill(300)("this_is_a_very_long_url/").mkString).take(URLFactory.MAX_URL_SIZE),
            "http://kifi.com")
        }
      }
    }
    "reactivate inactive bookmarks for the same url" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Greg", lastName = "Smith"))
        }
        val bookmarkInterner = inject[KeepInterner]
        val (initialBookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user.id.get, KeepSource.keeper, true)
        initialBookmarks.size === 1
        db.readWrite { implicit s =>
          keepRepo.save(keepRepo.getByUser(user.id.get).head.withActive(false))
        }
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmark(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user.id.get, KeepSource.keeper, true)
        db.readOnly { implicit s =>
          bookmarks.size === 1
          keepRepo.all.size === 1
        }
      }
    }
  }

}
