package com.keepit.commanders

import com.keepit.test._
import play.api.test.Helpers._
import org.specs2.mutable.Specification
import com.keepit.model._
import play.api.libs.json.Json
import com.keepit.common.healthcheck._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.heimdal.{KifiHitContext, SanitizedKifiHit, HeimdalContext}
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
        val (u1, u2, u3) = db.readWrite { implicit session =>
          val u1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
          val u2 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
          val u3 = userRepo.save(User(firstName = "Clicker", lastName = "ClicketyClickyClick"))
          (u1, u2, u3)
        }
        val bookmarkInterner = inject[KeepInterner]
        val raw1 = inject[RawBookmarkFactory].toRawBookmark(Json.arr(
          Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> false
          ), Json.obj(
            "url" -> "http://kifi.com",
            "isPrivate" -> false
          ))
        )

        val raw2 = inject[RawBookmarkFactory].toRawBookmark(Json.arr(
          Json.obj(
            "url" -> "http://kifi.com",
            "isPrivate" -> false
          ),
          Json.obj(
            "url" -> "http://google.com",
            "isPrivate" -> false
          ),
          Json.obj(
            "url" -> "http://bing.com",
            "isPrivate" -> false
          ))
        )

        val raw3 = inject[RawBookmarkFactory].toRawBookmark(Json.arr(
          Json.obj(
            "url" -> "http://kifi.com",
            "isPrivate" -> false
          ),
          Json.obj(
            "url" -> "http://stanford.edu",
            "isPrivate" -> false
          ))
        )

        val deduped = bookmarkInterner.deDuplicate(raw1)
        deduped === raw1
        deduped.size === 2

        val (keeps1, _) = bookmarkInterner.internRawBookmarks(raw1, u1.id.get, KeepSource.email, true)
        val (keeps2, _) = bookmarkInterner.internRawBookmarks(raw2, u2.id.get, KeepSource.default, true)
        keeps1.size === 2
        keeps2.size === 3
        keeps1(1).uriId === keeps2(0).uriId

        val (kc0, kc1, kc2) = db.readWrite { implicit rw =>

          val kifiHitCache = inject[KifiHitCache]
          val origin = "https://www.google.com"
          val kc0 = keepClickRepo.save(KeepClick(createdAt = currentDateTime, hitUUID = ExternalId[SanitizedKifiHit](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(0).id.get, uriId = keeps1(0).uriId))
          kifiHitCache.set(KifiHitKey(u2.id.get, keeps1(0).uriId), SanitizedKifiHit(kc0.hitUUID, origin, raw1(0).url, kc0.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId), Seq.empty, None, 0, 0)))

          val ts = currentDateTime
          val uuid = ExternalId[SanitizedKifiHit]()
          val kc1 = keepClickRepo.save(KeepClick(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId))
          val kc2 = keepClickRepo.save(KeepClick(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId))
          kifiHitCache.set(KifiHitKey(u3.id.get, keeps1(1).uriId), SanitizedKifiHit(kc1.hitUUID, origin, raw1(1).url, kc1.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId, u2.externalId), Seq.empty, None, 0, 0)))

          (kc0, kc1, kc2)
        }

        val (keeps3, _) = bookmarkInterner.internRawBookmarks(raw3, u3.id.get, KeepSource.default, true)

        db.readWrite { implicit session =>
          userRepo.get(u1.id.get) === u1
          keeps1.size === 2
          keepRepo.all.size === 7
          keeps2.size === 3
          keeps3.size === 2

          val allClicks = keepClickRepo.all()
          allClicks.size === 3

          val cu0 = keepClickRepo.getClicksByUUID(kc0.hitUUID)
          cu0.size === 1
          cu0(0).createdAt === kc0.createdAt
          cu0(0).hitUUID  === kc0.hitUUID
          cu0(0).keeperId === u1.id.get
          cu0(0).keepId   === keeps1(0).id.get

          val cu1 = keepClickRepo.getClicksByUUID(kc1.hitUUID)
          cu1.size === 2

          val c1 = cu1.find(_.keeperId == u1.id.get).get
          c1.createdAt === kc1.createdAt
          c1.createdAt === kc2.createdAt
          c1.hitUUID === kc2.hitUUID

          c1.keeperId === u1.id.get
          c1.keepId === keeps1(1).id.get

          val c2 = cu1.find(_.keeperId == u2.id.get).get
          c2.createdAt === kc1.createdAt
          c2.hitUUID === kc1.hitUUID
          c2.keeperId === u2.id.get
          c2.keepId === keeps2(0).id.get

          val ck1 = keepClickRepo.getClicksByKeeper(u1.id.get)
          ck1.size === 2

          val ck2 = keepClickRepo.getClicksByKeeper(u2.id.get)
          ck2.size === 1

          val counts1 = keepClickRepo.getClickCountsByKeeper(u1.id.get)
          println(s"counts1=${counts1.mkString(",")}")
          counts1.keySet.size === 2
          counts1.get(keeps1(0).id.get) === Some(1)
          counts1.get(keeps1(1).id.get) === Some(1)


          val counts2 = keepClickRepo.getClickCountsByKeeper(u2.id.get)
          println(s"counts2=${counts2.mkString(",")}")
          counts2.keySet.size === 1
          counts2.get(keeps2(0).id.get) === Some(1)
          counts2.get(keeps2(1).id.get) === None
          counts2.get(keeps2(2).id.get) === None

          val cm1 = keepClickRepo.getClickCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          println(s"cm1=${cm1.mkString(",")}")
          cm1.get(keeps1(0).id.get) === Some(1)
          cm1.get(keeps1(1).id.get) === Some(1)

          val cm2 = keepClickRepo.getClickCountsByKeepIds(u2.id.get, keeps2.map(_.id.get).toSet)
          println(s"cm2=${cm2.mkString(",")}")

          val rekeeps = rekeepRepo.all
          rekeeps.size === 2

          val rk1 = rekeeps.find(_.keeperId == u1.id.get).get
          rk1.keeperId === u1.id.get
          rk1.keepId === keeps1(1).id.get
          rk1.srcUserId === u3.id.get
          rk1.srcKeepId === keeps3(0).id.get

          val rk2 = rekeeps.find(_.keeperId == u2.id.get).get
          rk2.keeperId === u2.id.get
          rk2.keepId === keeps2(0).id.get
          rk2.srcUserId === u3.id.get
          rk2.srcKeepId === keeps3(0).id.get

          rekeepRepo.getReKeepsByKeeper(u1.id.get).head === rk1
          rekeepRepo.getReKeepsByKeeper(u2.id.get).head === rk2
          rekeepRepo.getReKeepsByReKeeper(u3.id.get).length === 2

          val rkc1 = rekeepRepo.getReKeepCountsByKeeper(u1.id.get)
          rkc1.get(keeps1(0).id.get) === None
          rkc1.get(keeps1(1).id.get) === Some(1)

          val rkc2 = rekeepRepo.getReKeepCountsByKeeper(u2.id.get)
          rkc2.get(keeps2(0).id.get) === Some(1)
          rkc2.get(keeps2(1).id.get) === None
          rkc2.get(keeps2(2).id.get) === None
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
