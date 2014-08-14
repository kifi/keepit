package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model._
import com.keepit.search.SearchServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class SeedAttributionHelper @Inject() (
    cortex: CortexServiceClient,
    search: SearchServiceClient) {

  def getAttributions(seeds: Seq[ScoredSeedItem]): Future[Seq[ScoredSeedItemWithAttribution]] = {
    val userAttrFut = getUserAttribution(seeds)
    val keepAttrFut = getKeepAttribution(seeds)
    val topicAttrFut = getTopicAttribution(seeds)
    for {
      userAttr <- userAttrFut
      keepAttr <- keepAttrFut
      topicAttr <- topicAttrFut
    } yield {
      (0 until seeds.size).map { i =>
        val attr = SeedAttribution(userAttr(i), keepAttr(i), topicAttr(i))
        ScoredSeedItemWithAttribution(seeds(i).userId, seeds(i).uriId, seeds(i).multiplier, seeds(i).uriScores, attr)
      }
    }
  }

  private def getUserAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[UserAttribution]]] = {
    require(seeds.map(_.userId).toSet.size <= 1, "Batch looking up of sharing users must be all for the same user")

    def needToLookup(seed: ScoredSeedItem) = seed.uriScores.socialScore > 0.5f

    val ret: Array[Option[UserAttribution]] = Array.fill(seeds.size)(None)

    seeds.headOption.map { _.userId } match {
      case None => Future.successful(ret)
      case Some(userId) =>
        // get uriIds for lookup and the corresponding indexes
        val (idxes, uriIds) = (0 until seeds.size).flatMap { i => if (needToLookup(seeds(i))) Some((i, seeds(i).uriId)) else None }.unzip
        if (uriIds.size == 0) {
          Future.successful(ret)
        } else {
          search.sharingUserInfo(userId, uriIds).map { sharingUsersInfo =>
            (idxes zip sharingUsersInfo).foreach {
              case (idx, info) =>
                if (info.sharingUserIds.size > 0) ret(idx) = Some(UserAttribution(info.sharingUserIds.toSeq, info.keepersEdgeSetSize - info.sharingUserIds.size))
            }
            ret
          }
        }
    }
  }

  private def getKeepAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[KeepAttribution]]] = {
    Future.successful(Seq.fill(seeds.size)(None))
  }

  private def getTopicAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[TopicAttribution]]] = {
    cortex.getTopicNames(seeds.map { _.uriId }).map { names =>
      names.map { nameOpt => nameOpt.map { TopicAttribution(_) } }
    }
  }

}
