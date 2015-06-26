package com.keepit.rover.store

import com.google.inject.Inject
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor }
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ Name, SystemValueRepo }
import com.keepit.rover.manager.BatchProcessingActor
import com.keepit.rover.model.{ ArticleKey, ArticleInfoRepo, RoverArticleInfo }

import scala.concurrent.{ ExecutionContext, Future }

class RoverArticleStoreMigrationActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database, articleInfoRepo: ArticleInfoRepo,
    systemValueRepo: SystemValueRepo,
    articleStore: RoverUnderlyingArticleStore,
    implicit val ec: ExecutionContext) extends FortyTwoActor(airbrake) with BatchProcessingActor[RoverArticleInfo] {

  val articleStoreMigration = Name[SequenceNumber[RoverArticleInfo]]("migration")
  val batchSize = 50

  protected val logger = log.logger

  protected def nextBatch = SafeFuture {
    db.readOnlyMaster { implicit session =>
      val seq = systemValueRepo.getSequenceNumber(articleStoreMigration) getOrElse SequenceNumber.ZERO
      articleInfoRepo.getBySequenceNumber(seq, batchSize)
    }
  }

  protected def processBatch(batch: Seq[RoverArticleInfo]): Future[Unit] = if (batch.isEmpty) Future.successful(()) else {
    val allCopied: Seq[Future[Unit]] = batch.map { articleInfo =>
      SafeFuture {
        articleInfo.oldestVersion.foreach { oldestVersion =>
          val latestVersion = articleInfo.latestVersion.get
          require(oldestVersion.major == latestVersion.major, s"[Unexpected] Major version has been incremented between $oldestVersion and $latestVersion")
          var version = oldestVersion
          while (version <= latestVersion) {
            val key = ArticleKey(articleInfo.uriId, articleInfo.urlHash, articleInfo.articleKind, version)
            val uriKey = UriIdArticleStoreKey(key)
            articleStore -= uriKey
            version = version.copy(minor = version.minor + 1)
          }
        }
      }
    }
    Future.sequence(allCopied).map { _ =>
      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(articleStoreMigration, batch.map(_.seq).max)
      }
    }
  }
}
