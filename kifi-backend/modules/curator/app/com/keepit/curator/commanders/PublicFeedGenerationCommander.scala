package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.model._
import com.keepit.model._
import com.keepit.common.logging.Logging

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class PublicFeedGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    publicScoringHelper: PublicUriScoringHelper,
    publicUriWeightingHelper: PublicUriWeightingHelper,
    db: Database,
    publicFeedRepo: PublicFeedRepo,
    uriRecRepo: UriRecommendationRepo,
    experimentCommander: RemoteUserExperimentCommander) extends Logging {

  val pubicFeedsGenerationLock = new ReactiveLock(1)
  val numPublicFeedLimit = 100

  private def specialCurators(): Future[Seq[Id[User]]] =
    experimentCommander.getUsersByExperiment(ExperimentType.SPECIAL_CURATOR).map(users => users.map(_.id.get).toSeq)

  private def computePublicMasterScore(baseScore: Float, scores: PublicUriScores): Float = {
    baseScore * scores.multiplier.getOrElse(1.0f)
  }

  private def savePublicScoredSeedItem(item: PublicScoredSeedItem, baseScore: Float) =
    db.readWrite { implicit s =>
      log.info(s"public feed: saving an item, uriId=${item.uriId}")
      val feedOpt = publicFeedRepo.getByUri(item.uriId)
      feedOpt.map { feed =>
        publicFeedRepo.save(feed.copy(
          publicMasterScore = computePublicMasterScore(baseScore, item.publicUriScores),
          publicAllScores = item.publicUriScores))
      } getOrElse {
        publicFeedRepo.save(PublicFeed(
          uriId = item.uriId,
          publicMasterScore = computePublicMasterScore(baseScore, item.publicUriScores),
          publicAllScores = item.publicUriScores))
      }
    }

  def clicked(uriId: Id[NormalizedURI]): Future[Unit] = score(uriId, "clicked")

  private def score(uriId: Id[NormalizedURI], reason: String): Future[Unit] = pubicFeedsGenerationLock.withLockFuture {
    try {
      log.info(s"public feed: scoring uriId=$uriId reason=$reason")
      val scoreOpt = db.readOnlyMaster { implicit session => uriRecRepo.getGeneralRecommendationScore(uriId, 2) }
      log.info(s"public feed: got score, uriId=$uriId score=$scoreOpt")

      scoreOpt match {
        case Some(score) =>
          val publicSeedItem = seedCommander.getPublicSeedItem(uriId).toSeq
          val cleanedItem = publicSeedItem.filter { publicSeedItem => //discard super popular items
            publicSeedItem.keepers match {
              case Keepers.ReasonableNumber(users) => true
              case _ => false
            }
          }
          val weightedItem = publicUriWeightingHelper(cleanedItem).filter(_.multiplier != 0.0f)
          specialCurators().map { boostedKeepersSeq =>
            publicScoringHelper(weightedItem, boostedKeepersSeq.toSet).map {
              case Seq(item) =>
                savePublicScoredSeedItem(item, score)
              case _ =>
                log.info(s"public feed: no item found, uriId=$uriId")
            }
          }
        case None =>
          log.info(s"public feed: not scored, uriId=$uriId")
          Future.successful(())
      }
    } catch {
      case ex: Throwable =>
        log.error(s"public feed: failed to score uriId=$uriId", ex)
        Future.failed(ex)
    }
  }

  def cleanup(): Future[Unit] = {
    pubicFeedsGenerationLock.withLock {
      db.readWrite { implicit session =>
        publicFeedRepo.cleanupOldFeeds(numPublicFeedLimit)
      }
    }
  }

  def startInitialLoading(): Unit = {
    log.info(s"public feed: getting initial candidates")
    val candidates = db.readOnlyReplica { implicit session =>
      uriRecRepo.getGeneralRecommendationCandidates(limit = numPublicFeedLimit)
    }
    log.info(s"public feed: got ${candidates.size} initial candidates")

    candidates.foreach { uriId => score(uriId, "initialLoading") }
  }
}
