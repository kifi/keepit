package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model._
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.GraphFeedExplanation
import com.keepit.model.{ Keep, NormalizedURI }
import com.keepit.search.SearchServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.collection.mutable
import scala.concurrent.Future

@Singleton
class SeedAttributionHelper @Inject() (
    db: Database,
    keepRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient,
    search: SearchServiceClient,
    graph: GraphServiceClient) {

  val MIN_KEEP_ATTR_SCORE = 2
  protected val MIN_USER_KEEP_SIZE = 20 // too few keeps means graph random walk may not return relevant results

  def getAttributions(seeds: Seq[ScoredSeedItem]): Future[Seq[ScoredSeedItemWithAttribution]] = {
    val userAttrFut = getUserAttribution(seeds)
    //val keepAttrFut = getKeepAttribution(seeds)
    val topicAttrFut = getTopicAttribution(seeds)
    for {
      userAttr <- userAttrFut
      //keepAttr <- keepAttrFut
      topicAttr <- topicAttrFut
    } yield {
      (0 until seeds.size).map { i =>
        val attr = SeedAttribution(userAttr(i), None /* keepAttr(i) */ , topicAttr(i))
        ScoredSeedItemWithAttribution(seeds(i).userId, seeds(i).uriId, seeds(i).uriScores, attr)
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
    require(seeds.map(_.userId).toSet.size <= 1, "Batch keep attribution must be all for the same user")

    val empty = Seq.fill(seeds.size)(None)

    seeds.headOption.map { _.userId } match {
      case None => Future.successful(empty)
      case Some(userId) =>
        val uriIds = seeds.map { _.uriId }
        val userUriKeepMap = db.readOnlyReplica { implicit s => keepRepo.getUserURIsAndKeeps(userId) }.toMap
        val userKeeps = userUriKeepMap.values.toSet
        if (userKeeps.size < MIN_USER_KEEP_SIZE) {
          Future.successful(empty)
        } else {
          graph.explainFeed(userId, uriIds).map { explains =>
            explains.map { ex => decodeGraphExplanation(ex, userUriKeepMap, userKeeps) }
          }
        }
    }

  }

  private def decodeGraphExplanation(graphExplain: GraphFeedExplanation, uriKeepMap: Map[Id[NormalizedURI], Id[Keep]], userKeeps: Set[Id[Keep]]): Option[KeepAttribution] = {
    val finalScores = mutable.Map[Id[Keep], Int]().withDefaultValue(0)
    graphExplain.keepScores.foreach {
      case (keep, score) =>
        if (userKeeps.contains(keep)) finalScores(keep) += score
    }

    graphExplain.uriScores.foreach {
      case (uri, score) =>
        uriKeepMap.get(uri).foreach { keep => finalScores(keep) += score }
    }
    val filtered = finalScores.filter { case (_, score) => score >= MIN_KEEP_ATTR_SCORE }.toArray
    val relevantKeeps = filtered.sortBy(-1 * _._2).take(3).map { _._1 }
    if (relevantKeeps.size == 0) None else Some(KeepAttribution(relevantKeeps))
  }

  private def getTopicAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[TopicAttribution]]] = {
    cortex.getTopicNames(seeds.map { _.uriId }).map { names =>
      names.map { nameOpt => nameOpt.map { TopicAttribution(_) } }
    }
  }

}
