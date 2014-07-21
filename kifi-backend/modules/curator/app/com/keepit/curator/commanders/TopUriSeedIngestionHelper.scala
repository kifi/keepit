package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ CuratorUriInfo, CuratorUriInfoRepo, RawSeedItem, RawSeedItemRepo }
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.ConnectedUriScore
import com.keepit.model._
import org.joda.time.DateTime

import scala.concurrent.Future

@Singleton
class TopUriSeedIngestionHelper @Inject() (
  systemValueRepo: SystemValueRepo,
  uriInfoRepo: CuratorUriInfoRepo,
  rawSeedsRepo: RawSeedItemRepo,
  db: Database,
  graph: GraphServiceClient) extends PersonalSeedIngestionHelper {

  private val SEQ_NUM_NAME: Name[SequenceNumber[NormalizedURI]] = Name("top_uri_seq_num")

  private def updateRawSeedItem(seedItem: RawSeedItem, uriId: Id[NormalizedURI], newDateCandidate: DateTime, countChange: Int)(implicit session: RWSession): Unit = {
    rawSeedsRepo.save(seedItem.copy(
      uriId = uriId //implicit renormalize :-)
      ))
  }

  def processUriScores(uriScore: ConnectedUriScore)(implicit session: RWSession): Unit = {
    uriInfoRepo.getByUriId(uriScore.uriId).map { uriInfo =>
      val seedItems = rawSeedsRepo.getByUriId(uriInfo.uriId)
      require(seedItems.length > 0)

      seedItems.foreach { seedItem =>

      }

      uriInfoRepo.save(uriInfo.copy(
        uriId = uriScore.uriId,
        score = uriScore.score))

    } getOrElse {
      uriInfoRepo.save(CuratorUriInfo(
        uriId = uriScore.uriId,
        score = uriScore.score))
    }
  }

  //triggers ingestions of up to maxItem RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  def apply(userId: Id[User], maxItems: Int): Future[Boolean] = {

    graph.getListOfUriAndScorePairs(userId, true).flatMap { uriScores =>
      db.readWriteAsync { implicit session =>
        uriScores.foreach(processUriScores)
      }
    }

  }
}
