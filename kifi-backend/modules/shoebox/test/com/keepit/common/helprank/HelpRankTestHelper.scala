package com.keepit.common.helprank

import com.google.inject.Injector
import com.keepit.commanders.{ LibraryCommander, RawBookmarkFactory, KeepInterner }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientImpl }
import com.keepit.model._
import com.keepit.search.ArticleSearchResult
import com.keepit.test.{ FakeIdCounter, TestInjector }
import play.api.libs.json.Json
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

trait HelpRankTestHelper { self: TestInjector =>

  def helpRankSetup(heimdal: FakeHeimdalServiceClientImpl, db: Database)(implicit context: HeimdalContext, injector: Injector): (User, User, User, Seq[Keep], Seq[Keep], Seq[Keep]) = {
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
    val keepFB = Json.obj("url" -> "http://facebook.com", "isPrivate" -> false)

    val (u1, u2, u3, u4) = db.readWrite { implicit session =>
      val u1 = UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
      val u2 = UserFactory.user().withName("Foo", "Bar").withUsername("test").saved
      val u3 = UserFactory.user().withName("Discoveryer", "DiscoveryetyDiscoveryyDiscovery").withUsername("test").saved
      val u4 = UserFactory.user().withName("Ro", "Bot").withUsername("test").saved
      (u1, u2, u3, u4)
    }

    val (u1m, u1s) = inject[LibraryCommander].internSystemGeneratedLibraries(u1.id.get)
    val (u2m, u2s) = inject[LibraryCommander].internSystemGeneratedLibraries(u2.id.get)
    val (u3m, u3s) = inject[LibraryCommander].internSystemGeneratedLibraries(u3.id.get)
    val (u4m, u4s) = inject[LibraryCommander].internSystemGeneratedLibraries(u4.id.get)

    val raw1 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keep42, keepKifi))
    val raw2 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepBing))
    val raw3 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepStanford, keepFB))
    val raw4 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepApple, keepFB))

    val (keeps1, _) = keepInterner.internRawBookmarks(raw1, u1.id.get, u1m, KeepSource.email)
    val (keeps2, _) = keepInterner.internRawBookmarks(raw2, u2.id.get, u2m, KeepSource.default)

    val kdCounter = new FakeIdCounter[KeepDiscovery]
    val kc0 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(0).id.get, uriId = keeps1(0).uriId)
    // u2 -> 42 (u1)

    val ts = currentDateTime
    val uuid = ExternalId[ArticleSearchResult]()
    val kc1 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId)
    val kc2 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId)
    // u3 -> kifi (u1, u2)
    heimdal.save(kc0, kc1, kc2)

    val (keeps3, _) = keepInterner.internRawBookmarks(raw3, u3.id.get, u3m, KeepSource.default)
    val kc3 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId)
    // u4 -> kifi (u3) [rekeep]
    heimdal.save(kc3)

    // u4 -> stanford (u3)
    val kc4 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = currentDateTime.plusMinutes(1), hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(1).id.get, uriId = keeps3(1).uriId)
    heimdal.save(kc4)
    // u4 -> FB (u3)
    val kc5 = KeepDiscovery(id = Some(kdCounter.nextId()), createdAt = currentDateTime.plusMinutes(2), hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(2).id.get, uriId = keeps3(2).uriId)
    heimdal.save(kc5)

    // unkeep u3.stanford
    val keepRepo = inject[KeepRepo]
    val keep31 = db.readWrite { implicit session => keepRepo.save(keeps3(1).copy(state = KeepStates.INACTIVE)) }
    val keeps3a = Seq(keeps3(0), keep31, keeps3(2))

    val rkCounter = new FakeIdCounter[ReKeep]
    val rk1 = ReKeep(id = Some(rkCounter.nextId()), keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId, srcKeepId = keeps3(0).id.get, srcUserId = u3.id.get)
    val rk2 = ReKeep(id = Some(rkCounter.nextId()), keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId, srcKeepId = keeps3(0).id.get, srcUserId = u3.id.get)
    heimdal.save(rk1, rk2)

    val (keeps4, _) = keepInterner.internRawBookmarks(raw4, u4.id.get, u4m, KeepSource.default)
    val rk3 = ReKeep(id = Some(rkCounter.nextId()), keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId, srcKeepId = keeps4(0).id.get, srcUserId = u4.id.get)
    heimdal.save(rk3)
    (u1, u2, u3, keeps1, keeps2, keeps3a)
  }

}
