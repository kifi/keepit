package com.keepit.curator.commanders

import com.google.inject.{ Inject }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.NamedStatsdTimer
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model._
import com.keepit.search.{ SearchServiceClient }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.CollectionHelpers

import scala.concurrent.Future
import com.keepit.search.augmentation.{ LimitedAugmentationInfo, AugmentableItem }

class SeedAttributionHelper @Inject() (
    db: Database,
    keepRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient,
    search: SearchServiceClient,
    libMemRepo: CuratorLibraryMembershipInfoRepo) {

  def getAttributions(seeds: Seq[ScoredSeedItem]): Future[Seq[ScoredSeedItemWithAttribution]] = {
    val timer = new NamedStatsdTimer("SeedAttributionHelper.getAttributions")
    val userAttrFut = getUserAttribution(seeds)
    for {
      userAttr <- userAttrFut
    } yield {
      timer.stopAndReport()
      (0 until seeds.size).map { i =>
        val attr = SeedAttribution(userAttr(i))
        ScoredSeedItemWithAttribution(seeds(i).userId, seeds(i).uriId, seeds(i).uriScores, attr,
          seeds(i).topic1, seeds(i).topic2)
      }
    }
  }

  private def getUserAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[UserAttribution]]] = {
    require(seeds.map(_.userId).toSet.size <= 1, "Batch looking up of sharing users must be all for the same user")
    val timer = new NamedStatsdTimer("SeedAttributionHelper.getUserAttribution")

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
          val uriIds = uriId2Idx.keys.toSeq
          search.augment(Some(userId), false, maxKeepersShown = 20, maxLibrariesShown = 15, maxTagsShown = 0, items = uriIds.map(AugmentableItem(_))).map { infos =>
            timer.stopAndReport()
            (uriIds zip infos).foreach {
              case (uriId, info) =>
                val idx = uriId2Idx(uriId)
                val attr = UserAttribution.fromLimitedAugmentationInfo(info)
                val n = attr.friends.size + attr.friendsLib.map { _.size }.getOrElse(0)
                if (n > 0) ret(idx) = Some(attr)
            }
            ret
          }
        }
    }
  }
}
