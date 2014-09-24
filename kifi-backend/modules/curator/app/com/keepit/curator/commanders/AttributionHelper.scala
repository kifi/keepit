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
    libMemRepo: CuratorLibraryMembershipInfoRepo) {

  def getAttributions(seeds: Seq[ScoredSeedItem]): Future[Seq[ScoredSeedItemWithAttribution]] = {
    val userAttrFut = getUserAttribution(seeds)
    val keepAttrFut = getKeepAttribution(seeds)
    val topicAttrFut = getTopicAttribution(seeds)
    val libraryAttrFut = getLibraryAttribution(seeds)
    for {
      userAttr <- userAttrFut
      keepAttr <- keepAttrFut
      topicAttr <- topicAttrFut
      libraryAttr <- libraryAttrFut
    } yield {
      (0 until seeds.size).map { i =>
        val attr = SeedAttribution(userAttr(i), keepAttr(i), topicAttr(i), libraryAttr(i))
        ScoredSeedItemWithAttribution(seeds(i).userId, seeds(i).uriId, seeds(i).uriScores, attr)
      }
    }
  }

  private def getLibraryAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[LibraryAttribution]]] = {
    db.readOnlyReplicaAsync { implicit session =>
      seeds.map { seed =>
        val libs = libMemRepo.getFollowedLibrariesWithUri(seed.userId, seed.uriId)
        if (libs.isEmpty) {
          None
        } else {
          Some(LibraryAttribution(libs.toSeq))
        }
      }
    }
  }

  private def getUserAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[UserAttribution]]] = {
    require(seeds.map(_.userId).toSet.size <= 1, "Batch looking up of sharing users must be all for the same user")

    def needToLookup(seed: ScoredSeedItem) = seed.uriScores.socialScore > 0.1f

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
        cortex.explainFeed(userId, uriIds).map { res =>
          res.map { keepIds => if (!keepIds.isEmpty) Some(KeepAttribution(keepIds)) else None }
        }
    }
  }

  private def getTopicAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[TopicAttribution]]] = {
    cortex.getTopicNames(seeds.map { _.uriId }).map { names =>
      names.map { nameOpt => nameOpt.map { TopicAttribution(_) } }
    }
  }

}
