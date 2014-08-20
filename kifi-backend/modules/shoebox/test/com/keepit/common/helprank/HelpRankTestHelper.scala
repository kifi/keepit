package com.keepit.common.helprank

import com.google.inject.Injector
import com.keepit.commanders.{ RawBookmarkFactory, KeepInterner }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientImpl }
import com.keepit.model._
import com.keepit.search.ArticleSearchResult
import com.keepit.test.{ FakeIdCounter, TestInjector }
import play.api.libs.json.Json

trait HelpRankTestHelper { self: TestInjector =>

  def helpRankSetup(heimdal: FakeHeimdalServiceClientImpl, db: Database)(implicit context: HeimdalContext, injector: Injector): (User, User, Seq[Keep]) = {
    implicit val kdRepo = heimdal.keepDiscoveryRepo
    implicit val rkRepo = heimdal.rekeepRepo

    val userRepo = inject[UserRepo]
    val keepInterner = inject[KeepInterner]

    val keep42 = Json.obj("url" -> "http://42go.com", "isPrivate" -> false)
    val keepKifi = Json.obj("url" -> "http://kifi.com", "isPrivate" -> false)
    val keepGoog = Json.obj("url" -> "http://google.com", "isPrivate" -> false)
    val keepBing = Json.obj("url" -> "http://bing.com", "isPrivate" -> false)
    val keepStanford = Json.obj("url" -> "http://stanford.edu", "isPrivate" -> false)
    val keepApple = Json.obj("url" -> "http://apple.com", "isPrivate" -> false)

    val (u1, u2, u3, u4) = db.readWrite { implicit session =>
      val u1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
      val u2 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
      val u3 = userRepo.save(User(firstName = "Discoveryer", lastName = "DiscoveryetyDiscoveryyDiscovery"))
      val u4 = userRepo.save(User(firstName = "Ro", lastName = "Bot"))
      (u1, u2, u3, u4)
    }

    val raw1 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keep42, keepKifi))
    val raw2 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepBing))
    val raw3 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepStanford))
    val raw4 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepApple))

    val (keeps1, _) = keepInterner.internRawBookmarks(raw1, u1.id.get, KeepSource.email, true)
    val (keeps2, _) = keepInterner.internRawBookmarks(raw2, u2.id.get, KeepSource.default, true)

    val kdCounter = new FakeIdCounter[KeepDiscovery]
    val kc0 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(0).id.get, uriId = keeps1(0).uriId)
    // u2 -> 42 (u1)

    val ts = currentDateTime
    val uuid = ExternalId[ArticleSearchResult]()
    val kc1 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId)
    val kc2 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId)
    // u3 -> kifi (u1, u2)
    heimdal.save(kc0, kc1, kc2)

    val (keeps3, _) = keepInterner.internRawBookmarks(raw3, u3.id.get, KeepSource.default, true)
    val kc3 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId)
    // u4 -> kifi (u3) [rekeep]
    heimdal.save(kc3)

    val rkCounter = new FakeIdCounter[ReKeep]
    val rk1 = ReKeep(id = Some(rkCounter.nextId()), keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId, srcKeepId = keeps3(0).id.get, srcUserId = u3.id.get)
    val rk2 = ReKeep(id = Some(rkCounter.nextId()), keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId, srcKeepId = keeps3(0).id.get, srcUserId = u3.id.get)
    heimdal.save(rk1, rk2)

    val (keeps4, _) = keepInterner.internRawBookmarks(raw4, u4.id.get, KeepSource.default, true)
    val rk3 = ReKeep(id = Some(rkCounter.nextId()), keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId, srcKeepId = keeps4(0).id.get, srcUserId = u4.id.get)
    heimdal.save(rk3)
    (u1, u2, keeps1)
  }

}
