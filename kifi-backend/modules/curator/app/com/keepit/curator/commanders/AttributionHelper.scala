package com.keepit.curator.commanders

import com.google.inject.{ Inject }
import com.keepit.common.db.slick.Database
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model._
import com.keepit.search.{ SearchServiceClient }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import com.keepit.search.augmentation.{ RestrictedKeepInfo, ItemAugmentationRequest, FullAugmentationInfo, AugmentableItem }

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
        val uriId2Idx = (0 until seeds.size).flatMap { i => if (needToLookup(seeds(i))) Some((seeds(i).uriId, i)) else None }.toMap
        if (uriId2Idx.size == 0) {
          Future.successful(ret)
        } else {
          val request = ItemAugmentationRequest.uniform(userId, uriId2Idx.keys.toSeq.map { uriId => AugmentableItem(uriId) }: _*)
          search.augmentation(request).map { resp =>
            resp.infos.foreach {
              case (item, info) =>
                val idx = uriId2Idx(item.uri)
                val attr = toUserAttribution(info)
                val n = attr.friends.size + attr.friendsLib.map { _.size }.getOrElse(0)
                if (n > 0) ret(idx) = Some(attr)
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

  def toUserAttribution(info: FullAugmentationInfo): UserAttribution = {
    val users = info.keeps.flatMap(_.keptBy).distinct
    val user2Lib = info.keeps.flatMap {
      case RestrictedKeepInfo(_, Some(libId), Some(userId), _) => Some((userId, libId))
      case _ => None
    }.toMap
    val others = info.otherDiscoverableKeeps + info.otherPublishedKeeps
    UserAttribution(users, others, Some(user2Lib))
  }
}
