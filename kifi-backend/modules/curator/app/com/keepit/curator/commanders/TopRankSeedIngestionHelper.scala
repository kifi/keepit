package com.keepit.curator.commanders

import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RawSeedItemRepo
import com.keepit.graph.GraphServiceClient
import com.keepit.model.{SystemValueRepo, User}

import scala.concurrent.Future

@Singleton
class TopRankSeedIngestionHelper @Inject() (
  systemValueRepo: SystemValueRepo,
  rawSeedsRepo: RawSeedItemRepo,
  db: Database,
  graph: GraphServiceClient ) extends PersonalSeedIngestionHelper {

  def processUris() {

  }

  def processUsers() {

  }

  //triggers ingestions of up to maxItem RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  def apply(userId: Id[User], maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[_]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[_](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      graph.getListOfUriAndScorePairs(userId, true).flatMap { uris =>
        db.readWriteAsync { implicit session =>
          uris.foreach(processUris)
          if (uris.length > 0) false//systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, uris.map(_.).max)
          uris.length >= maxItems
        }
      }
    }

  }
}
