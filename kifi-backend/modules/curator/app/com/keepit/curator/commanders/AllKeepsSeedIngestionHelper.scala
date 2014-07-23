package com.keepit.curator.commanders

import com.keepit.model.{ SystemValueRepo, Name, Keep, NormalizedURI, KeepStates }
import com.keepit.curator.model.{ CuratorKeepInfoRepo, RawSeedItemRepo, RawSeedItem, CuratorKeepInfoStates, CuratorKeepInfo }
import com.keepit.common.db.{ SequenceNumber, State }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

import com.google.inject.{ Inject, Singleton }

import org.joda.time.DateTime

@Singleton
class AllKeepSeedIngestionHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    keepInfoRepo: CuratorKeepInfoRepo,
    rawSeedsRepo: RawSeedItemRepo,
    db: Database,
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {

  private val SEQ_NUM_NAME: Name[SequenceNumber[Keep]] = Name("all_keeps_seq_num")

  private def updateRawSeedItem(seedItem: RawSeedItem, uriId: Id[NormalizedURI], newDateCandidate: DateTime, countChange: Int)(implicit session: RWSession): Unit = {
    rawSeedsRepo.save(seedItem.copy(
      uriId = uriId, //implicit renormalize :-)
      firstKept = if (newDateCandidate.isBefore(seedItem.firstKept)) newDateCandidate else seedItem.firstKept,
      lastKept = if (newDateCandidate.isAfter(seedItem.lastKept)) newDateCandidate else seedItem.lastKept,
      lastSeen = if (newDateCandidate.isAfter(seedItem.lastSeen)) newDateCandidate else seedItem.lastSeen,
      timesKept = seedItem.timesKept + countChange
    ))
  }

  private def processKeep(keep: Keep)(implicit session: RWSession): Unit = {
    keepInfoRepo.getByKeepId(keep.id.get).map { keepInfo =>
      val seedItems = rawSeedsRepo.getByUriId(keepInfo.uriId)
      log.info(s"Got seed items: ${seedItems} for keepInfo ${keepInfo} and keep ${keep}")
      require(seedItems.length > 0, s"Missing RSI: keepId ${keepInfo.keepId}, uriId ${keepInfo.uriId}") //note that here we look up with the possible old uri id from the local keep info repo and deal with renormalization later, hence the require
      val countChange = if (keep.state.value != keepInfo.state.value) {
        if (keepInfo.state == CuratorKeepInfoStates.ACTIVE) -1 else if (keep.state == KeepStates.ACTIVE) 1 else 0
      } else 0
      seedItems.foreach { seedItem =>
        updateRawSeedItem(seedItem, keep.uriId, keep.createdAt, countChange)
      }
      keepInfoRepo.save(keepInfo.copy(
        uriId = keep.uriId,
        userId = keep.userId,
        state = State[CuratorKeepInfo](keep.state.value)
      ))
    } getOrElse {
      keepInfoRepo.save(CuratorKeepInfo(
        uriId = keep.uriId,
        userId = keep.userId,
        keepId = keep.id.get,
        state = State[CuratorKeepInfo](keep.state.value)
      ))

      val seedItems = rawSeedsRepo.getByUriId(keep.uriId)
      if (seedItems.isEmpty) {
        rawSeedsRepo.save(RawSeedItem(
          uriId = keep.uriId,
          userId = None,
          firstKept = keep.createdAt,
          lastKept = keep.createdAt,
          lastSeen = keep.createdAt,
          priorScore = None,
          timesKept = if (keep.state == KeepStates.ACTIVE) 1 else 0
        ))
      } else {
        seedItems.foreach { seedItem =>
          updateRawSeedItem(seedItem, keep.uriId, keep.createdAt, if (keep.state == KeepStates.ACTIVE) 1 else 0)
        }
      }

    }

  }

  def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[Keep]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[Keep](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getBookmarksChanged(lastSeqNum, maxItems).flatMap { keeps =>
        db.readWriteAsync { implicit session =>
          keeps.foreach(processKeep)
          if (keeps.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, keeps.map(_.seq).max)
          keeps.length >= maxItems
        }
      }
    }
  }

}
