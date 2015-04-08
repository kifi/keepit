package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.SequenceNumber
import com.keepit.rover.article.content.{ NormalizationInfoHolder, HttpInfoHolder }
import com.keepit.rover.model.{ ShoeboxArticleUpdates, ArticleInfo, ShoeboxArticleUpdate }
import com.keepit.rover.sensitivity.RoverSensitivityCommander
import com.keepit.rover.store.RoverArticleStore
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }

@Singleton
class RoverCommander @Inject() (
    articleCommander: ArticleCommander,
    sensitivityCommander: RoverSensitivityCommander,
    articleStore: RoverArticleStore,
    private implicit val executionContext: ExecutionContext) {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = {
    val updatedInfos = articleCommander.getArticleInfosBySequenceNumber(seq, limit)
    if (updatedInfos.isEmpty) Future.successful(None)
    else {
      val futureUpdates = updatedInfos.flatMap { info =>
        info.getLatestKey.map { latestKey =>
          articleStore.get(latestKey).map { latestArticleOption =>
            latestArticleOption.map(toShoeboxArticleUpdate(info))
          }
        }
      }
      val maxSeq = updatedInfos.map(_.seq).max
      Future.sequence(futureUpdates).imap { updates =>
        Some(ShoeboxArticleUpdates(updates.flatten, maxSeq))
      }
    }
  }

  private def toShoeboxArticleUpdate(articleInfo: ArticleInfo)(latestArticle: articleInfo.A) = {
    ShoeboxArticleUpdate(
      articleInfo.uriId,
      articleInfo.kind,
      latestArticle.url,
      latestArticle.createdAt,
      latestArticle.content.title,
      sensitivityCommander.isSensitive(latestArticle),
      Some(latestArticle.content).collect { case httpContent: HttpInfoHolder => httpContent.http },
      Some(latestArticle.content).collect { case normalizationContent: NormalizationInfoHolder => normalizationContent.normalization }
    )
  }
}
