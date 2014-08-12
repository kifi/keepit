package com.keepit.curator.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model.ScoredSeedItem
import com.keepit.model.{ Keep, User }
import com.kifi.macros.json

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@json case class UserAttribution(friends: Seq[Id[User]], others: Int)
@json case class KeepAttribution(keeps: Seq[Id[Keep]])
@json case class TopicAttribution(topicName: String)
@json case class SeedAttribution(user: Option[UserAttribution], keep: Option[KeepAttribution], topic: Option[TopicAttribution])

@ImplementedBy(classOf[SeedAttributionHelperImpl])
trait SeedAttributionHelper {

  def getSeedAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[SeedAttribution]] = {
    val userAttrFut = getUserAttribution(seeds)
    val keepAttrFut = getKeepAttribution(seeds)
    val topicAttrFut = getTopicAttribution(seeds)
    for {
      userAttr <- userAttrFut
      keepAttr <- keepAttrFut
      topicAttr <- topicAttrFut
    } yield {
      (0 until seeds.size).map { i =>
        SeedAttribution(userAttr(i), keepAttr(i), topicAttr(i))
      }
    }
  }

  def getUserAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[UserAttribution]]]
  def getKeepAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[KeepAttribution]]]
  def getTopicAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[TopicAttribution]]]
}

@Singleton
class SeedAttributionHelperImpl @Inject() (cortex: CortexServiceClient) extends SeedAttributionHelper {

  def getUserAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[UserAttribution]]] = {
    Future.successful(Seq.fill(seeds.size)(None))
  }

  def getKeepAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[KeepAttribution]]] = {
    Future.successful(Seq.fill(seeds.size)(None))
  }

  def getTopicAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[TopicAttribution]]] = {
    cortex.getTopicNames(seeds.map { _.uriId }).map { names =>
      names.map { nameOpt => nameOpt.map { TopicAttribution(_) } }
    }
  }

}
