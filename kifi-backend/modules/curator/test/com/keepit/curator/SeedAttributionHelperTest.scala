package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.cortex.FakeCortexServiceClientImpl
import com.keepit.curator.commanders.SeedAttributionHelper
import com.keepit.curator.model.{ UriScores, ScoredSeedItem }
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.search.{ SharingUserInfo, FakeSearchServiceClient }
import org.specs2.mutable.Specification

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._

class SeedAttributionHelperTest extends Specification {

  val fakeSearch = new FakeSearchServiceClient() {

    private def genFakeSharingUserInfo(uriId: Id[NormalizedURI]): SharingUserInfo = {
      if (uriId.id > 5) SharingUserInfo(Set(), 0)
      else {
        val userIds = (1 to uriId.id.toInt).map { Id[User](_) }.toSet
        SharingUserInfo(userIds, userIds.size + uriId.id.toInt)
      }
    }

    override def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
      Future.successful(uriIds.map { genFakeSharingUserInfo(_) })
    }
  }

  val fakeCortex = new FakeCortexServiceClientImpl(null) {
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
    discoveryScore = 0f)

  val scoredItem1 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](1), emptyScore.copy(socialScore = 0.01f))
  val scoredItem2 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](2), emptyScore.copy(socialScore = 0.9f))
  val scoredItem3 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](20), emptyScore.copy(socialScore = 0.9f))
  val scoredItem4 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](3), emptyScore.copy(socialScore = 0.9f))
  val scoredItem5 = ScoredSeedItem(Id[User](1), Id[NormalizedURI](4), emptyScore.copy(socialScore = 0.02f))
  val scoredItems = Seq(scoredItem1, scoredItem2, scoredItem3, scoredItem4, scoredItem5)

  "Seed Attribution helper" should {
    "work" in {
      val attrHelper = new SeedAttributionHelper(fakeCortex, fakeSearch)
      val itemsWithAttr = Await.result(attrHelper.getAttributions(scoredItems), Duration(5, "seconds"))
      itemsWithAttr(0).attribution.topic.get.topicName === "topic_1"
      itemsWithAttr(0).attribution.user === None

      itemsWithAttr(1).attribution.user.get.friends.map { _.id } === List(1, 2)
      itemsWithAttr(1).attribution.user.get.others === 2
      itemsWithAttr(2).attribution.user === None
      itemsWithAttr(3).attribution.user.get.friends.map { _.id } === List(1, 2, 3)
      itemsWithAttr(3).attribution.user.get.others === 3
      itemsWithAttr(4).attribution.user === None
      itemsWithAttr(4).attribution.topic.get.topicName === "topic_4"
    }
  }

}
