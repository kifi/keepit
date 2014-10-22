package com.keepit.curator

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.cortex.FakeCortexServiceClientImpl
import com.keepit.curator.commanders.SeedAttributionHelper
import com.keepit.curator.model._
import com.keepit.graph.FakeGraphServiceClientImpl
import com.keepit.graph.model.GraphFeedExplanation
import com.keepit.model.{ Library, Keep, NormalizedURI, User }
import com.keepit.search._
import org.specs2.mutable.Specification

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.search.augmentation._
import com.keepit.curator.model.ScoredSeedItem
import scala.Some
import com.keepit.curator.model.CuratorKeepInfo
import com.keepit.curator.model.UriScores

class SeedAttributionHelperTest extends Specification with CuratorTestInjector {

  val fakeSearch = new FakeSearchServiceClient() {

    private def genAugmentationInfo(uriId: Id[NormalizedURI]): FullAugmentationInfo = {
      val n = uriId.id.toInt
      if (n > 5) return FullAugmentationInfo(Seq(), 0, 0, -1)

      val keepInfo = (1 to n) map { i =>
        val extId = ExternalId.apply[Keep]()
        val lib = if (i % 2 == 1) Some(Id[Library](i)) else None // half of the users have associated lib
        val user = Some(Id[User](i))
        RestrictedKeepInfo(extId, lib, user, Set())
      }
      val otherPublishedKeeps = n
      val otherDiscoverableKeeps = n
      FullAugmentationInfo(keepInfo, otherPublishedKeeps, otherDiscoverableKeeps, -1)
    }

    override def augmentation(request: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
      val augs = request.items.map { case item => (item, genAugmentationInfo(item.uri)) }.toMap
      val scores = AugmentationScores(Map(), Map(), Map())
      Future.successful(ItemAugmentationResponse(augs, scores))
    }
  }

  val fakeCortex = new FakeCortexServiceClientImpl(null) {
    override def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Seq[Id[Keep]]]] = {
      Future.successful(uriIds.map { uid => if (uid.id.toInt % 2 == 1) Seq.empty[Id[Keep]] else Seq(Id[Keep](uid.id)) })
    }
    override def getTopicNames(uris: Seq[Id[NormalizedURI]]): Future[Seq[Option[String]]] = {
      val names = uris.map { uri => Some("topic_" + uri.id) }
      Future.successful(names)
    }
  }

  val emptyScore = UriScores(
    socialScore = 0f,
    popularityScore = 0f,
    overallInterestScore = 0f,
    recentInterestScore = 0f,
    recencyScore = 0f,
    priorScore = 0f,
    rekeepScore = 0f,
    discoveryScore = 0f,
    curationScore = None,
    multiplier = Some(1.0f),
    libraryInducedScore = Some(0f))

  val scoredItem1 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](1), emptyScore.copy(socialScore = 0.01f))
  val scoredItem2 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](2), emptyScore.copy(socialScore = 0.9f))
  val scoredItem3 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](20), emptyScore.copy(socialScore = 0.9f))
  val scoredItem4 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](3), emptyScore.copy(socialScore = 0.9f))
  val scoredItem5 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](4), emptyScore.copy(socialScore = 0.02f))
  val scoredItems = Seq(scoredItem1, scoredItem2, scoredItem3, scoredItem4, scoredItem5)

  val defaultKeep = CuratorKeepInfo(uriId = Id[NormalizedURI](1), userId = Id[User](1), keepId = Id[Keep](1),
    libraryId = Some(Id[Library](1)), state = CuratorKeepInfoStates.ACTIVE, discoverable = true)

  "Seed Attribution helper" should {
    "work" in {
      withDb() { implicit injector =>
        val db = inject[Database]
        val repo = inject[CuratorKeepInfoRepo]

        db.readWrite { implicit s =>
          (1 to 5).foreach { i =>
            repo.save(defaultKeep.copy(uriId = Id[NormalizedURI](i), keepId = Id[Keep](i)))
          }
        }

        val attrHelper = new SeedAttributionHelper(db, repo, fakeCortex, fakeSearch, inject[CuratorLibraryMembershipInfoRepo])

        val itemsWithAttr = Await.result(attrHelper.getAttributions(scoredItems), Duration(5, "seconds"))
        itemsWithAttr(0).attribution.topic.get.topicName === "topic_1"
        itemsWithAttr(0).attribution.user === None
        itemsWithAttr(0).attribution.keep === None

        itemsWithAttr(1).attribution.user.get.friends.map { _.id } === List(1, 2)
        itemsWithAttr(1).attribution.user.get.friendsLib.get.map { case (userId, libId) => (userId.id, libId.id) }.toMap === Map(1 -> 1)
        itemsWithAttr(1).attribution.user.get.others === 4

        itemsWithAttr(2).attribution.user === None

        itemsWithAttr(3).attribution.user.get.friends.map { _.id } === List(1, 2, 3)
        itemsWithAttr(3).attribution.user.get.friendsLib.get.map { case (userId, libId) => (userId.id, libId.id) }.toMap === Map(1 -> 1, 3 -> 3)
        itemsWithAttr(3).attribution.user.get.others === 6

        itemsWithAttr(4).attribution.user === None
        itemsWithAttr(4).attribution.topic.get.topicName === "topic_4"
        itemsWithAttr(4).attribution.keep.get.keeps.map { _.id }.toList === List(4)
      }
    }
  }

}
