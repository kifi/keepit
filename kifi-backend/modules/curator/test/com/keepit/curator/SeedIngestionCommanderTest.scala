package com.keepit.curator

import com.keepit.common.net.FakeHttpClientModule
import com.keepit.graph.{ FakeGraphServiceClientImpl, GraphServiceClient, FakeGraphServiceModule }
import org.specs2.mutable.Specification

import com.keepit.common.db.slick._
import com.keepit.test.DbTestInjector
import com.keepit.shoebox.{ ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.model.{ User, Keep, KeepSource, NormalizedURI, URL, KeepStates, SystemValueRepo, Name }
import com.keepit.curator.model._
import com.keepit.curator.commanders.SeedIngestionCommander
import com.keepit.common.cache.TestCacheModule

import com.google.inject.Injector

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.joda.time.DateTime
import com.keepit.common.concurrent.ExecutionContext

class SeedIngestionCommanderTest extends Specification with DbTestInjector {

  private def modules = {
    Seq(
      FakeShoeboxServiceModule(),
      FakeGraphServiceModule(),
      FakeHttpClientModule(),
      TestCacheModule()
    )
  }

  private def makeKeeps(userId: Id[User], howMany: Int, shoebox: FakeShoeboxServiceClientImpl): Seq[Keep] = {
    (1 to howMany).flatMap { i =>
      shoebox.saveBookmarks(Keep(
        uriId = Id[NormalizedURI](i),
        urlId = Id[URL](i),
        url = "https://kifi.com",
        userId = userId,
        state = KeepStates.ACTIVE,
        source = KeepSource.keeper,
        libraryId = None))
    }
  }

  private def setup()(implicit injector: Injector) = {
    val user1 = Id[User](42)
    val user2 = Id[User](43)

    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    shoebox.saveUsers(User(
      id = Some(user1),
      firstName = "Some",
      lastName = "User42"))
    shoebox.saveUsers(User(
      id = Some(user2),
      firstName = "Some",
      lastName = "User43"))

    (user1, user2, shoebox)
  }

  "SeedIngestionCommander" should {

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
          keepInfoRepo.getByKeepId(user1Keeps(0).id.get).get.state.value == KeepStates.INACTIVE.value
          keepInfoRepo.getByKeepId(user1Keeps(0).id.get).get.state.value == KeepStates.DUPLICATE.value
          keepInfoRepo.getByKeepId(user1Keeps(7).id.get).get.state.value == KeepStates.ACTIVE.value
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
          val seedItem1: Option[RawSeedItem] = seedItemRepo.getByUriIdAndUserId(user1Keeps.head.uriId, user1)
          seedItem1.get.priorScore === Some(0.795.toFloat)
          seedItem1.get.userId === Some(Id[User](42))
          seedItem1.get.uriId === Id[NormalizedURI](1)

          val seedItem2: Option[RawSeedItem] = seedItemRepo.getByUriIdAndUserId(user1Keeps.head.uriId, user2)
          seedItem2 === None
        }
      }

    }

  }
}

