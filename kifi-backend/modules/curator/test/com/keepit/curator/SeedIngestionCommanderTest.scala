package com.keepit.curator

import com.keepit.common.net.FakeHttpClientModule
import com.keepit.graph.{ FakeGraphServiceClientImpl, GraphServiceClient, FakeGraphServiceModule }
import org.specs2.mutable.Specification

import com.keepit.common.db.slick._
import com.keepit.shoebox.{ ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.model.{ User, Keep, KeepSource, NormalizedURI, URL, KeepStates, SystemValueRepo, Name }
import com.keepit.curator.model._
import com.keepit.curator.commanders.SeedIngestionCommander
import com.keepit.common.cache.FakeCacheModule

import com.google.inject.Injector

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.joda.time.DateTime
import com.keepit.common.concurrent.ExecutionContext

class SeedIngestionCommanderTest extends Specification with CuratorTestInjector {

  import TestHelpers.{ makeKeeps, makeUser, makeKeepsWithPrivacy }

  private def modules = {
    Seq(
      FakeShoeboxServiceModule(),
      FakeGraphServiceModule(),
      FakeHttpClientModule(),
      FakeCacheModule()
    )
  }

  private def setup()(implicit injector: Injector) = {
    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val (user1, user2) = (makeUser(42, shoebox).id.get, makeUser(43, shoebox).id.get)

    (user1, user2, shoebox)
  }

  "SeedIngestionCommander" should {

    "ingest keeps and have discoverability correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeepsWithPrivacy(user1, 5, true, shoebox)
        val keepInfoRepo = inject[CuratorKeepInfoRepo]
        val seedItemRepo = inject[RawSeedItemRepo]
        val commander = inject[SeedIngestionCommander]

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        var seedItems = db.readOnlyMaster { implicit session => seedItemRepo.all() }
        seedItems.foreach(_.discoverable === false)

        shoebox.saveBookmarks(user1Keeps(4).copy(
          userId = user2,
          isPrivate = false))
        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        seedItems = db.readOnlyMaster { implicit session => seedItemRepo.all() }

        seedItems(4).discoverable === true

        shoebox.saveBookmarks(user1Keeps(4).copy(
          isPrivate = true))
        shoebox.saveBookmarks(Keep(
          uriId = Id[NormalizedURI](5),
          urlId = Id[URL](5),
          url = "https://kifi.com",
          userId = user1,
          state = KeepStates.ACTIVE,
          source = KeepSource.keeper,
          isPrivate = true,
          libraryId = None))
        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        seedItems = db.readOnlyMaster { implicit session => seedItemRepo.all() }

        seedItems(4).discoverable === false

        shoebox.saveBookmarks(user1Keeps(4).copy(
          isPrivate = false,
          state = KeepStates.INACTIVE))

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        seedItems = db.readOnlyMaster { implicit session => seedItemRepo.all() }

        seedItems(4).discoverable === false
      }
    }

    //this is in one test case instead of a bunch to reduce run time (i.e. avoid repeated db initialization) as we are moving quite a bit of data.
    //Already takes several seconds as it is.
    "ingest multiple batches and update sequence number and raw seed items correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeeps(user1, 30, shoebox)
        val user2Keeps = makeKeeps(user2, 30, shoebox)
        val keepInfoRepo = inject[CuratorKeepInfoRepo]
        val systemValueRepo = inject[SystemValueRepo]
        val seedItemRepo = inject[RawSeedItemRepo]
        val commander = inject[SeedIngestionCommander]

        db.readOnlyMaster { implicit session => keepInfoRepo.all() }.length === 0
        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readOnlyMaster { implicit session => keepInfoRepo.all() }.length === 60
        db.readOnlyMaster { implicit session => systemValueRepo.getSequenceNumber(Name[SequenceNumber[Keep]]("all_keeps_seq_num")).get } === SequenceNumber[Keep](60)

        var seedItemsBefore = db.readOnlyMaster { implicit session => seedItemRepo.all() }
        seedItemsBefore.length === 30
        seedItemsBefore.foreach { seedItem =>
          seedItem.userId === None
          seedItem.priorScore === None
          seedItem.timesKept === 2
        }

        shoebox.saveBookmarks(user1Keeps(0).copy(
          uriId = Id[NormalizedURI](47),
          state = KeepStates.INACTIVE))

        shoebox.saveBookmarks(user1Keeps(1).copy(
          state = KeepStates.DUPLICATE))

        shoebox.saveBookmarks(user1Keeps(2).copy(
          state = KeepStates.ACTIVE))

        shoebox.saveBookmarks(user1Keeps(3).copy(
          createdAt = new DateTime(1997, 12, 14, 6, 44, 32)))

        shoebox.saveBookmarks(user2Keeps(3).copy(
          createdAt = new DateTime(2022, 10, 1, 9, 18, 44)))

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readOnlyMaster { implicit session =>
          keepInfoRepo.all().length === 60
          keepInfoRepo.getByKeepId(user1Keeps(0).id.get).get.state.value === KeepStates.INACTIVE.value
          keepInfoRepo.getByKeepId(user1Keeps(1).id.get).get.state.value === KeepStates.DUPLICATE.value
          keepInfoRepo.getByKeepId(user1Keeps(7).id.get).get.state.value === KeepStates.ACTIVE.value
          keepInfoRepo.getByKeepId(user1Keeps(0).id.get).get.uriId === Id[NormalizedURI](47)
          seedItemRepo.getByUriId(user1Keeps(0).uriId).length === 0
          seedItemRepo.getByUriId(Id[NormalizedURI](47)).length === 1

        }

        var seedItemsAfter1 = db.readOnlyMaster { implicit session => seedItemRepo.all() }
        seedItemsAfter1.length === 30
        seedItemsAfter1.foreach { seedItem =>
          seedItem.userId === None
          seedItem.priorScore === None
          if (seedItem.uriId == Id[NormalizedURI](47) || seedItem.uriId == user1Keeps(1).uriId) {
            seedItem.timesKept === 1
          } else {
            seedItem.timesKept === 2
          }

          if (seedItem.uriId == user1Keeps(3).uriId) {
            seedItem.lastSeen.getMillis() === new DateTime(2022, 10, 1, 9, 18, 44).getMillis()
            seedItem.firstKept.getMillis() === new DateTime(1997, 12, 14, 6, 44, 32).getMillis()
            seedItem.lastKept.getMillis() === new DateTime(2022, 10, 1, 9, 18, 44).getMillis()
          }
        }

        shoebox.saveBookmarks(user1Keeps(0).copy(
          state = KeepStates.DUPLICATE))

        shoebox.saveBookmarks(user1Keeps(1).copy(
          state = KeepStates.ACTIVE))

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readOnlyMaster { implicit session => keepInfoRepo.all() }.length === 60

        var seedItemsAfter2 = db.readOnlyMaster { implicit session => seedItemRepo.all() }
        seedItemsAfter2.length === 30
        seedItemsAfter2.foreach { seedItem =>
          seedItem.userId === None
          seedItem.priorScore === None
          if (seedItem.uriId == user1Keeps(0).uriId) {
            seedItem.timesKept === 1
          } else {
            seedItem.timesKept === 2
          }
        }

        1 === 1

      }
    }

    "retrieve seed items by sequence number correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeeps(user1, 5, shoebox)
        val user2Keeps = makeKeeps(user2, 6, shoebox)
        val commander = inject[SeedIngestionCommander]
        val seedItemRepo = inject[RawSeedItemRepo]

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readWrite { implicit session => seedItemRepo.assignSequenceNumbers(1000) }

        val user1SeedItems = Await.result(commander.getBySeqNumAndUser(SequenceNumber.ZERO, user1, 20), Duration(10, "seconds"))
        val user2SeedItems = Await.result(commander.getBySeqNumAndUser(SequenceNumber.ZERO, user2, 20), Duration(10, "seconds"))

        user1SeedItems.length === 6
        user2SeedItems.length === 6

        user1SeedItems.foreach { seedItem =>
          seedItem.timesKept === (if (seedItem.uriId == Id[NormalizedURI](6)) 1 else 2)
          seedItem.userId === user1
        }

        user2SeedItems.foreach { seedItem =>
          seedItem.userId === user2
        }

        1 === 1
      }
    }

    "retrieve recent items correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeeps(user1, 5, shoebox)
        val commander = inject[SeedIngestionCommander]
        val seedItemRepo = inject[RawSeedItemRepo]

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readWrite { implicit session => seedItemRepo.assignSequenceNumbers(1000) }

        val items = Await.result(commander.getRecentItems(user1, 2), Duration(10, "seconds"))
        items.length === 2
        items.foreach { item =>
          item.userId === user1
          item.uriId.id > 3
        }

        1 === 1
      }
    }

    "merge items (on uriId merge) correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeeps(user1, 2, shoebox)
        val commander = inject[SeedIngestionCommander]
        val seedItemRepo = inject[RawSeedItemRepo]

        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readOnlyMaster { implicit session => seedItemRepo.all().length === 2 }

        shoebox.saveBookmarks(user1Keeps(0).copy(uriId = user1Keeps(1).uriId))
        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))

        val allItems = db.readOnlyMaster { implicit session => seedItemRepo.all() }

        allItems.length === 1
        allItems(0).uriId === user1Keeps(1).uriId
        allItems(0).timesKept === 2

      }
    }

    "ingest top score uris into raw seed items" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeeps(user1, 10, shoebox)
        makeKeeps(user2, 5, shoebox)
        val seedItemRepo = inject[RawSeedItemRepo]
        val commander = inject[SeedIngestionCommander]
        Await.result(commander.ingestAllKeeps(), Duration(10, "seconds"))

        val graph = inject[GraphServiceClient].asInstanceOf[FakeGraphServiceClientImpl]
        graph.setUriAndScorePairs(user1Keeps.map { x => x.uriId }.toList)

        val result = commander.ingestTopUris(user1)
        Await.result(result, Duration(10, "seconds"))

        db.readOnlyMaster { implicit session =>
          val seedItem1: Option[RawSeedItem] = seedItemRepo.getByUriIdAndUserId(user1Keeps.head.uriId, Some(user1))
          seedItem1.get.priorScore === Some(0.795f)
          seedItem1.get.userId === Some(Id[User](42))
          seedItem1.get.uriId === Id[NormalizedURI](1)

          val seedItem2: Option[RawSeedItem] = seedItemRepo.getByUriIdAndUserId(user1Keeps.head.uriId, Some(user2))
          seedItem2 === None
        }
      }

    }

  }
}

