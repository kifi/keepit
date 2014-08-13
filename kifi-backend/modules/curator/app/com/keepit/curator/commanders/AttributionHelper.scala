package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model._
import com.keepit.model.{ Keep, User }
import com.kifi.macros.json

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class SeedAttributionHelper @Inject() (cortex: CortexServiceClient) {

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
        ScoredSeedItemWithAttribution(seeds(i).userId, seeds(i).uriId, seeds(i).uriScores, attr)
      }
    }
  }

  private def getUserAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[UserAttribution]]] = {
    Future.successful(Seq.fill(seeds.size)(None))
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
