package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{
  Keepers,
  PublicUriScores,
  PublicScoredSeedItem,
  PublicFeed,
  PublicFeedRepo,
  PublicSeedItem
}
import com.keepit.model.{ ExperimentType, User, Name, SystemValueRepo }
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class PublicFeedGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    publicScoringHelper: PublicUriScoringHelper,
    publicUriWeightingHelper: PublicUriWeightingHelper,
    db: Database,
    publicFeedRepo: PublicFeedRepo,
    systemValueRepo: SystemValueRepo,
    experimentCommander: RemoteUserExperimentCommander) {

  val pubicFeedsGenerationLock = new ReactiveLock(1)
  private val SEQ_NUM_NAME: Name[SequenceNumber[PublicSeedItem]] = Name("public_feeds_seq_num")
  private def specialCurators(): Future[Seq[Id[User]]] =
    experimentCommander.getUsersByExperiment(ExperimentType.SPECIAL_CURATOR).map(users => users.map(_.id.get).toSeq)

  private def computePublicMasterScore(scores: PublicUriScores): Float = {
    (1 * scores.recencyScore +
      1 * scores.popularityScore +
      6 * scores.rekeepScore +
      3 * scores.discoveryScore +
      4 * scores.curationScore.getOrElse(0.0f)) *
      scores.multiplier.getOrElse(1.0f)
  }

  private def getPublicFeedCandidateSeeds(seq: SequenceNumber[PublicSeedItem]) =
    for {
      seeds <- seedCommander.getBySeqNum(seq, 200)
      candidateURIs <- shoebox.getCandidateURIs(seeds.map { _.uriId })
    } yield {
      ((seeds zip candidateURIs) filter (_._2) map (_._1), if (seeds.isEmpty) seq else seeds.map(_.seq).max)
    }

  private def savePublicScoredSeedItems(items: Seq[PublicScoredSeedItem], newSeqNum: SequenceNumber[PublicSeedItem]) =
    db.readWrite { implicit s =>
      items foreach { item =>
        val feedOpt = publicFeedRepo.getByUri(item.uriId, None)
        feedOpt.map { feed =>
          publicFeedRepo.save(feed.copy(
            publicMasterScore = computePublicMasterScore(item.publicUriScores),
            publicAllScores = item.publicUriScores))
        } getOrElse {
          publicFeedRepo.save(PublicFeed(
            uriId = item.uriId,
            publicMasterScore = computePublicMasterScore(item.publicUriScores),
            publicAllScores = item.publicUriScores))
        }
      }
      systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, newSeqNum)
    }

  private def getPrecomputationFeedsResult(publicSeedsAndSeqFuture: Future[(Seq[PublicSeedItem], SequenceNumber[PublicSeedItem])],
    lastSeqNum: SequenceNumber[PublicSeedItem], boostedKeepers: Set[Id[User]]) =
    publicSeedsAndSeqFuture.flatMap {
      case (publicSeedItems, newSeqNum) =>
        if (publicSeedItems.isEmpty) {
          db.readWriteAsync { implicit session =>
            systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, newSeqNum)
          }
          if (lastSeqNum < newSeqNum) precomputePublicFeeds()
          Future.successful(false)
        } else {
          val cleanedItems = publicSeedItems.filter { publicSeedItem => //discard super popular items and the users own keeps
            publicSeedItem.keepers match {
              case Keepers.ReasonableNumber(users) => true
              case _ => false
            }
          }
          val weightedItems = publicUriWeightingHelper(cleanedItems).filter(_.multiplier != 0.0f)
          publicScoringHelper(weightedItems, boostedKeepers).map { items =>
            savePublicScoredSeedItems(items, newSeqNum)
            precomputePublicFeeds()
            publicSeedItems.nonEmpty
          }
        }
    }

  def precomputePublicFeeds(): Future[Unit] = pubicFeedsGenerationLock.withLockFuture {
    specialCurators().flatMap { boostedKeepersSeq =>

      val lastSeqNumFut: Future[SequenceNumber[PublicSeedItem]] = db.readOnlyMasterAsync { implicit session =>
        systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse {
          SequenceNumber[PublicSeedItem](0)
        }
      }

      lastSeqNumFut.flatMap { lastSeqNum =>
        val publicSeedsAndSeqFuture: Future[(Seq[PublicSeedItem], SequenceNumber[PublicSeedItem])] = getPublicFeedCandidateSeeds(lastSeqNum)
        val res: Future[Boolean] = getPrecomputationFeedsResult(publicSeedsAndSeqFuture, lastSeqNum, boostedKeepersSeq.toSet)
        res.map(_ => ())
      }
    }
  }
}
