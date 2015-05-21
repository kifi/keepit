package com.keepit.rover

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.core._
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.model.{ NormalizedURI }
import com.keepit.rover.article.{ ArticleKind, Article, ArticleCommander }
import com.keepit.rover.article.content.{ HttpInfoHolder, NormalizationInfoHolder }
import com.keepit.rover.document.utils.Signature
import com.keepit.rover.fetcher.{InvalidFetchResponseException, InvalidFetchRequestException}
import com.keepit.rover.image.ImageCommander
import com.keepit.rover.model._
import com.keepit.rover.sensitivity.RoverSensitivityCommander
import com.keepit.rover.store.ContentSignatureCommander
import com.keepit.common.time._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class RoverCommander @Inject() (
    articleCommander: ArticleCommander,
    imageCommander: ImageCommander,
    sensitivityCommander: RoverSensitivityCommander,
    articleSummaryCache: RoverArticleSummaryCache,
    articleImagesCache: RoverArticleImagesCache,
    signatureCommander: ContentSignatureCommander,
    private implicit val executionContext: ExecutionContext,
    clock: Clock,
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

  def getBestArticleSummaryByUris[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Future[Map[Id[NormalizedURI], RoverArticleSummary]] = {
    articleCommander.getBestArticleByUris[A](uriIds).imap { articleOptionByUriId =>
      articleOptionByUriId.collect {
        case (uriId, Some(article)) =>
          uriId -> RoverArticleSummary.fromArticle(article)
      }
    }
  }

  def getImagesByUris[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Map[Id[NormalizedURI], BasicImages] = {
    imageCommander.getImageInfosByUrisAndArticleKind[A](uriIds).mapValues { imageInfos =>
      BasicImages(imageInfos.map(BasicImage.fromBaseImage))
    }
  }

  def getOrElseFetchArticleSummaryAndImages[A <: Article](uriId: Id[NormalizedURI], url: String)(implicit kind: ArticleKind[A]): Future[Option[(RoverArticleSummary, BasicImages)]] = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

    val futureArticleSummaryOption = {
      val key = RoverArticleSummaryKey(uriId, kind)
      articleSummaryCache.get(key) match {
        case Some(articleSummary) => Future.successful(Some(articleSummary))
        case None => articleCommander.getOrElseFetchBestArticle[A](uriId, url).map { articleOption =>
          val fetchedSummaryOpt = articleOption.map(RoverArticleSummary.fromArticle)
          fetchedSummaryOpt tap (articleSummaryCache.set(key, _))
        }
      }
    }

    futureArticleSummaryOption.flatMap {
      case None => Future.successful(None)
      case Some(articleSummary) => {
        imageCommander.processLatestArticleImagesIfNecessary[A](uriId).map {
          case () =>
            val key = RoverArticleImagesKey(uriId, kind)
            val images = articleImagesCache.getOrElse(key) {
              getImagesByUris[A](Set(uriId)).getOrElse(uriId, BasicImages.empty)
            }
            Some((articleSummary, images))
        }
      }
    }
  }

  def getOrElseComputeRecentContentSignature[A <: Article](url: String, recency: Duration)(implicit kind: ArticleKind[A]): Future[Option[Signature]] = {
    val signatureRecencyLimit = clock.now().minusSeconds(recency.toSeconds.toInt)
    signatureCommander.getCachedUrlSignature[A](url) match {
      case Some(urlSignature) if urlSignature.computedAt isAfter signatureRecencyLimit => Future.successful(urlSignature.signature)
      case _ => {
        articleCommander.getOrElseFetchRecentArticle[A](url, recency)(kind).recover {
          case invalidRequest: InvalidFetchRequestException => None
          case invalidResponse: InvalidFetchResponseException => None
        } flatMap {
          articleOpt => signatureCommander.computeUrlSignature(url, articleOpt)
        }
      }
    }
  }
}

