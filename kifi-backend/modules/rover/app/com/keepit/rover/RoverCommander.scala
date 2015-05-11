package com.keepit.rover

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.core._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.article.ArticleCommander
import com.keepit.rover.article.content.{ HttpInfoHolder, NormalizationInfoHolder }
import com.keepit.rover.article.policy.ArticleInfoPolicy
import com.keepit.rover.model.{ ArticleInfo, ShoeboxArticleUpdate, ShoeboxArticleUpdates }
import com.keepit.rover.sensitivity.RoverSensitivityCommander

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class RoverCommander @Inject() (
    articleCommander: ArticleCommander,
    sensitivityCommander: RoverSensitivityCommander,
    articlePolicy: ArticleInfoPolicy,
    private implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = {
    val updatedInfos = articleCommander.getArticleInfosBySequenceNumber(seq, limit)
    if (updatedInfos.isEmpty) Future.successful(None)
    else {
      val futureUpdates = updatedInfos.map { info =>
        articleCommander.getLatestArticle(info).imap { latestArticleOption =>
          latestArticleOption.map(toShoeboxArticleUpdate(info))
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
      latestArticle.content.destinationUrl,
      latestArticle.createdAt,
      latestArticle.content.title,
      sensitivityCommander.isSensitive(latestArticle),
      Some(latestArticle.content).collect { case httpContent: HttpInfoHolder => httpContent.http },
      Some(latestArticle.content).collect { case normalizationContent: NormalizationInfoHolder => normalizationContent.normalization }
    )
  }
}
