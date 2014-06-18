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
import play.api.libs.json.Json.JsValueWrapper
import com.keepit.common.concurrent.ExecutionContext.fj
import scala.concurrent.duration._
import scala.concurrent.Await

class KeepInternerTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty
  implicit val system = ActorSystem("test")
  implicit val execCtx = fj

  def modules = FakeKeepImportsModule() :: FakeScrapeSchedulerModule() :: Nil

  val keep42   = Json.obj("url" -> "http://42go.com", "isPrivate" -> false)
  val keepKifi = Json.obj("url" -> "http://kifi.com", "isPrivate" -> false)
  val keepGoog = Json.obj("url" -> "http://google.com", "isPrivate" -> false)
  val keepBing = Json.obj("url" -> "http://bing.com", "isPrivate" -> false)
  val keepStanford = Json.obj("url" -> "http://stanford.edu", "isPrivate" -> false)
  val keepApple = Json.obj("url" -> "http://apple.com", "isPrivate" -> false)

  "BookmarkInterner" should {

    "persist bookmark" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[KeepInterner]
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmarks(Json.obj(
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
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), keepKifi))
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
        val (u1, u2, u3, u4) = db.readWrite { implicit session =>
          val u1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
          val u2 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
          val u3 = userRepo.save(User(firstName = "Clicker", lastName = "ClicketyClickyClick"))
          val u4 = userRepo.save(User(firstName = "Ro", lastName = "Bot"))
          (u1, u2, u3, u4)
        }
        val bookmarkInterner = inject[KeepInterner]
        val raw1 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keep42, keepKifi))
        val raw2 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepBing))
        val raw3 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepStanford))
        val raw4 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepApple))

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
          // u2 -> 42 (u1)
          kifiHitCache.set(KifiHitKey(u2.id.get, keeps1(0).uriId), SanitizedKifiHit(kc0.hitUUID, origin, raw1(0).url, kc0.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId), Seq.empty, None, 0, 0)))

          val ts = currentDateTime
          val uuid = ExternalId[SanitizedKifiHit]()
          val kc1 = keepClickRepo.save(KeepClick(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId))
          val kc2 = keepClickRepo.save(KeepClick(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId))
          // u3 -> kifi (u1, u2) [rekeep]
          kifiHitCache.set(KifiHitKey(u3.id.get, keeps1(1).uriId), SanitizedKifiHit(kc1.hitUUID, origin, raw1(1).url, kc1.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId, u2.externalId), Seq.empty, None, 0, 0)))

          (kc0, kc1, kc2)
        }

        val (keeps3, _) = bookmarkInterner.internRawBookmarks(raw3, u3.id.get, KeepSource.default, true)

        val kc3 = db.readWrite { implicit rw =>
          val kifiHitCache = inject[KifiHitCache]
          val origin = "https://www.google.com"
          val kc3 = keepClickRepo.save(KeepClick(createdAt = currentDateTime, hitUUID = ExternalId[SanitizedKifiHit](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId))
          // u4 -> kifi (u3) [rekeep]
          kifiHitCache.set(KifiHitKey(u4.id.get, keeps3(0).uriId), SanitizedKifiHit(kc3.hitUUID, origin, raw3(0).url, kc3.uriId, KifiHitContext(false, false, 0, Seq(u3.externalId), Seq.empty, None, 0, 0)))
          kc3
        }

        val (keeps4, _) = bookmarkInterner.internRawBookmarks(raw4, u4.id.get, KeepSource.default, true)

        db.readWrite { implicit session =>
          userRepo.get(u1.id.get) === u1
          keeps1.size === 2
          keepRepo.all.size === 10
          keeps2.size === 3
          keeps3.size === 2
          keeps4.size === 3

          val allClicks = keepClickRepo.all()
          allClicks.size === 4

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
          counts1.keySet.size === 2
          counts1.get(keeps1(0).id.get) === Some(1)
          counts1.get(keeps1(1).id.get) === Some(1)
          val uriCounts1 = keepClickRepo.getUriClickCountsByKeeper(u1.id.get)
          uriCounts1.size === counts1.size
          uriCounts1.map(_._2).toSeq.sorted === counts1.map(_._2).toSeq.sorted
          uriCounts1.forall { case (uriId, count) =>
            val keep = keeps1.find(_.uriId == uriId).get
            counts1.get(keep.id.get).get == count
          } === true

          val counts2 = keepClickRepo.getClickCountsByKeeper(u2.id.get)
          counts2.keySet.size === 1
          counts2.get(keeps2(0).id.get) === Some(1)
          counts2.get(keeps2(1).id.get) === None
          counts2.get(keeps2(2).id.get) === None

          keepClickRepo.getClickCountByKeeper(u1.id.get) === ck1.size
          keepClickRepo.getClickCountByKeeper(u2.id.get) === ck2.size
          keepClickRepo.getClickCountByKeeper(u3.id.get) === keepClickRepo.getClicksByKeeper(u3.id.get).length
          keepClickRepo.getClickCountByKeeper(u4.id.get) === keepClickRepo.getClicksByKeeper(u4.id.get).length

          val cm1 = keepClickRepo.getClickCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          cm1.get(keeps1(0).id.get) === Some(1)
          cm1.get(keeps1(1).id.get) === Some(1)

          val cm2 = keepClickRepo.getClickCountsByKeepIds(u2.id.get, keeps2.map(_.id.get).toSet)

          val rekeeps = rekeepRepo.all
          rekeeps.size === 3

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

          val rkmap1 = rekeepRepo.getReKeeps(Set(keeps1(1).id.get))
          val rkseq1 = rkmap1(keeps1(1).id.get)
          rkseq1.length === 1
          rkseq1(0).keepId === keeps1(1).id.get
          rkseq1(0).srcUserId === u3.id.get
          rkseq1(0).srcKeepId === keeps3(0).id.get

          val rkmap2 = rekeepRepo.getReKeeps(Set(keeps2(0).id.get))
          val rkseq2 = rkmap2(keeps2(0).id.get)
          rkseq2.length === 1
          rkseq2(0).keepId === keeps2(0).id.get
          rkseq2(0).srcUserId === u3.id.get
          rkseq2(0).srcKeepId === keeps3(0).id.get

          val rkmap3 = rekeepRepo.getReKeeps(Set(keeps3(0).id.get))
          val rkseq3 = rkmap3(keeps3(0).id.get)
          rkseq3.length === 1
          rkseq3(0).keepId === keeps3(0).id.get
          rkseq3(0).srcUserId === u4.id.get
          rkseq3(0).srcKeepId === keeps4(0).id.get

          val rkbk1 = rekeepRepo.getReKeepsByKeeper(u1.id.get)
          rkbk1.head === rk1
          rekeepRepo.getAllReKeepsByKeeper(u1.id.get) === rkbk1

          val rkbk2 = rekeepRepo.getReKeepsByKeeper(u2.id.get)
          rkbk2.head === rk2
          rekeepRepo.getReKeepsByReKeeper(u3.id.get).length === 2
          rekeepRepo.getAllReKeepsByKeeper(u2.id.get) === rkbk2

          val rkc1 = rekeepRepo.getReKeepCountsByKeeper(u1.id.get)
          rkc1.get(keeps1(0).id.get) === None
          rkc1.get(keeps1(1).id.get) === Some(1)
          val rkc1a = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          rkc1a.size === rkc1.size
          keeps1.forall { keep =>
            rkc1.get(keep.id.get) == rkc1a.get(keep.id.get)
          } === true
          val rkc1b = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, Set(keeps1(1).id.get))
          rkc1b.size === 1
          rkc1b.get(keeps1(1).id.get) === rkc1.get(keeps1(1).id.get)
          val rkc1c = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, Set(keeps1(0).id.get))
          rkc1c.size === 0

          val uriRKC1 = rekeepRepo.getUriReKeepCountsByKeeper(u1.id.get)
          uriRKC1.size === rkc1.size
          uriRKC1.forall { case (uri, count) =>
            val keep = keeps1.find(_.uriId == uri).get
            rkc1.get(keep.id.get).get == count
          } === true

          val rkc2 = rekeepRepo.getReKeepCountsByKeeper(u2.id.get)
          rkc2.get(keeps2(0).id.get) === Some(1)
          rkc2.get(keeps2(1).id.get) === None
          rkc2.get(keeps2(2).id.get) === None

          rekeepRepo.getReKeepCountByKeeper(u1.id.get) === rkc1.valuesIterator.foldLeft(0) {(a,c) => a + c}
          rekeepRepo.getReKeepCountByKeeper(u2.id.get) === rkc2.valuesIterator.foldLeft(0) {(a,c) => a + c}
          rekeepRepo.getReKeepCountByKeeper(u3.id.get) === 1
          rekeepRepo.getReKeepCountByKeeper(u4.id.get) === 0

          val bkMap1 = keepRepo.bulkGetByUserAndUriIds(u1.id.get, keeps1.map(_.uriId).toSet)
          bkMap1.forall { case (uriId, keep) =>
            keeps1.find(_.uriId == uriId).get == keep
          } === true
          val bkMap2 = keepRepo.bulkGetByUserAndUriIds(u2.id.get, keeps2.map(_.uriId).toSet)
          bkMap2.forall { case (uriId, keep) =>
            keeps2.find(_.uriId == uriId).get == keep
          } === true
        }

        val attrCmdr = inject[AttributionCommander]
        val rkbd1 = attrCmdr.getReKeepsByDegree(u1.id.get, keeps1(1).id.get, 3)
        rkbd1.length === 3
        val (ubd1, kbd1) = rkbd1.unzip
        ubd1(0) === Set(u1.id.get)
        ubd1(1) === Set(u3.id.get)
        ubd1(2) === Set(u4.id.get)
        kbd1(0) === Set(keeps1(1).id.get)
        kbd1(1) === Set(keeps3(0).id.get)
        kbd1(2) === Set(keeps4(0).id.get)

        db.readOnly { implicit ro => userBookmarkClicksRepo.getByUserUri(u1.id.get, keeps1(1).uriId) } === None
        val bc1 = Await.result(attrCmdr.updateUserReKeepStatus(u1.id.get), Duration.Inf)
        bc1.nonEmpty === true
        bc1.length === 1
        bc1(0).rekeepCount === 1
        bc1(0).rekeepTotalCount === 2

        db.readOnly { implicit ro => userBookmarkClicksRepo.getByUserUri(u2.id.get, keeps2(0).uriId) } === None
        db.readOnly { implicit ro => userBookmarkClicksRepo.getByUserUri(u3.id.get, keeps3(0).uriId) } === None

        val bc3 = Await.result(attrCmdr.updateUserReKeepStatus(u3.id.get), Duration.Inf)
        bc3(0).rekeepCount === 1
        bc3(0).rekeepTotalCount === 1

        val users = Seq(u1, u2, u3, u4)
        val allStats = Await.result(attrCmdr.updateUsersReKeepStats(users.map(_.id.get)), Duration.Inf)
        allStats.foreach { s => println(s"(len=${s.length}); ${s.mkString(",")})") }
        allStats(0).length === bc1.length
        allStats(0)(0).rekeepCount === bc1(0).rekeepCount
        allStats(0)(0).rekeepTotalCount === bc1(0).rekeepTotalCount

        allStats(2).length === bc3.length
        allStats(2)(0).rekeepCount === bc3(0).rekeepCount
        allStats(2)(0).rekeepTotalCount === bc3(0).rekeepTotalCount
      }
    }

    "dedup on" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), keep42))
        bookmarkInterner.deDuplicate(raw).size === 1
      }
    }
    "dedup off" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://43go.com",
          "isPrivate" -> true
        ), keep42))
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
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
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
        val (initialBookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user.id.get, KeepSource.keeper, true)
        initialBookmarks.size === 1
        db.readWrite { implicit s =>
          keepRepo.save(keepRepo.getByUser(user.id.get).head.withActive(false))
        }
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
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
