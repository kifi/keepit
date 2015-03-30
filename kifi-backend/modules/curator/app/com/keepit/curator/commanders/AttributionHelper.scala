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
    val keepAttrFut = getKeepAttribution(seeds)
    val topicAttrFut = getTopicAttribution(seeds)
    val libraryAttrFut = getLibraryAttribution(seeds)
    for {
      userAttr <- userAttrFut
      keepAttr <- keepAttrFut
      topicAttr <- topicAttrFut
      libraryAttr <- libraryAttrFut
    } yield {
      timer.stopAndReport()
      (0 until seeds.size).map { i =>
        val attr = SeedAttribution(userAttr(i), keepAttr(i), topicAttr(i), libraryAttr(i))
        ScoredSeedItemWithAttribution(seeds(i).userId, seeds(i).uriId, seeds(i).uriScores, attr,
          seeds(i).topic1, seeds(i).topic2)
      }
    }
  }

  private def getLibraryAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[LibraryAttribution]]] = {
    val timer = new NamedStatsdTimer("SeedAttributionHelper.getLibraryAttribution")
    db.readOnlyReplicaAsync { implicit session =>
      seeds.map { seed =>
        val libs = libMemRepo.getFollowedLibrariesWithUri(seed.userId, seed.uriId)
        timer.stopAndReport()
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
    val timer = new NamedStatsdTimer("SeedAttributionHelper.getKeepAttribution")

    val empty = Seq.fill(seeds.size)(None)
    seeds.headOption.map { _.userId } match {
      case None => Future.successful(empty)
      case Some(userId) =>
        val uriIds = seeds.map { _.uriId }
        cortex.explainFeed(userId, uriIds).map { res =>
          timer.stopAndReport()
          res.map { keepIds => if (!keepIds.isEmpty) Some(KeepAttribution(keepIds)) else None }
        }
    }
  }

  private def getTopicAttribution(seeds: Seq[ScoredSeedItem]): Future[Seq[Option[TopicAttribution]]] = {
    val timer = new NamedStatsdTimer("SeedAttributionHelper.getTopicAttribution")
    cortex.getTopicNames(seeds.map { _.uriId }, seeds.headOption.map { _.userId }).map { names =>
      timer.stopAndReport()
      names.map { nameOpt => nameOpt.map { TopicAttribution(_) } }
    }
  }

  def toUserAttribution(info: LimitedAugmentationInfo): UserAttribution = {
    val others = info.keepersTotal - info.keepers.size - info.keepersOmitted
    val userToLib = CollectionHelpers.dedupBy(info.libraries)(_._2).map(_.swap).toMap // a user could have kept this page in several libraries, retain the first (most relevant) one.
    UserAttribution(info.keepers, others, Some(userToLib))
  }
}
