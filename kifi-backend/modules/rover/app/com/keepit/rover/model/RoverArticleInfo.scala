package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.rover.article.fetcher.ArticleFetchRequest
import com.keepit.rover.article.policy.{ FailureRecoveryPolicy, FetchSchedulingPolicy }
import com.keepit.rover.article.{ ArticleKind, Article }
import org.joda.time.DateTime
import scala.concurrent.duration.Duration

object ArticleInfoStates extends States[RoverArticleInfo]

// Warning: Do not cache - we're not using a strict ORM approach with this model (see saveSilently, update).
case class RoverArticleInfo(
    id: Option[Id[RoverArticleInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverArticleInfo] = ArticleInfoStates.ACTIVE,
    seq: SequenceNumber[RoverArticleInfo] = SequenceNumber.ZERO,
    uriId: Id[NormalizedURI],
    url: String,
    urlHash: UrlHash,
    kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
    bestVersion: Option[ArticleVersion] = None,
    latestVersion: Option[ArticleVersion] = None,
    oldestVersion: Option[ArticleVersion] = None,
    lastFetchedAt: Option[DateTime] = None,
    nextFetchAt: Option[DateTime] = None,
    lastFetchingAt: Option[DateTime] = None,
    fetchInterval: Option[Duration] = None,
    failureCount: Int = 0,
    failureInfo: Option[String] = None,
    imageProcessingRequestedAt: Option[DateTime] = None,
    lastImageProcessingVersion: Option[ArticleVersion] = None,
    lastImageProcessingAt: Option[DateTime] = None) extends ModelWithState[RoverArticleInfo] with ModelWithSeqNumber[RoverArticleInfo] with ArticleInfoHolder with ArticleKindHolder {

  def withId(id: Id[RoverArticleInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == ArticleInfoStates.ACTIVE)

  def getFetchRequest: ArticleFetchRequest[A] = ArticleFetchRequest(articleKind, url, getLatestKey)

  def clean: RoverArticleInfo = copy(
    bestVersion = None,
    latestVersion = None,
    oldestVersion = None,
    lastFetchedAt = None,
    nextFetchAt = None,
    lastFetchingAt = None,
    fetchInterval = None,
    failureCount = 0,
    failureInfo = None,
    imageProcessingRequestedAt = None,
    lastImageProcessingVersion = None,
    lastImageProcessingAt = None
  )

  private def schedulingPolicy = FetchSchedulingPolicy(articleKind)

  def initializeSchedulingPolicy: RoverArticleInfo = copy(
    nextFetchAt = Some(currentDateTime),
    fetchInterval = Some(schedulingPolicy.initialInterval)
  )

  def withLatestFetchComplete: RoverArticleInfo = copy(
    lastFetchedAt = Some(currentDateTime),
    lastFetchingAt = None
  )

  def withFailure(error: Throwable)(implicit recoveryPolicy: FailureRecoveryPolicy): RoverArticleInfo = {
    val updatedFailureCount = failureCount + 1
    val updatedNextFetchAt = recoveryPolicy.nextFetch(url, error, updatedFailureCount)
    copy(
      nextFetchAt = updatedNextFetchAt,
      failureCount = updatedFailureCount,
      failureInfo = Some(error.toString)
    )
  }

  def withLatestArticle(version: ArticleVersion): RoverArticleInfo = {
    val decreasedFetchInterval = fetchInterval.map(schedulingPolicy.decreaseInterval)
    copy(
      nextFetchAt = decreasedFetchInterval.map(schedulingPolicy.nextFetch),
      fetchInterval = decreasedFetchInterval,
      latestVersion = Some(version),
      oldestVersion = oldestVersion orElse Some(version),
      failureCount = 0,
      failureInfo = None,
      imageProcessingRequestedAt = Some(currentDateTime)
    )
  }

  def withoutChange: RoverArticleInfo = {
    val increasedFetchInterval = fetchInterval.map(schedulingPolicy.increaseInterval)
    copy(
      nextFetchAt = increasedFetchInterval.map(schedulingPolicy.nextFetch),
      fetchInterval = increasedFetchInterval,
      failureCount = 0,
      failureInfo = None
    )
  }

  def withImageProcessingComplete(version: Option[ArticleVersion]) = {
    copy(
      imageProcessingRequestedAt = None,
      lastImageProcessingVersion = version orElse lastImageProcessingVersion,
      lastImageProcessingAt = None
    )
  }
}

object RoverArticleInfo {
  implicit def toArticleInfoSeq(seq: SequenceNumber[RoverArticleInfo]): SequenceNumber[ArticleInfo] = seq.copy()
  implicit def fromArticleInfoSeq(seq: SequenceNumber[ArticleInfo]): SequenceNumber[RoverArticleInfo] = seq.copy()
  implicit def toArticleInfoState(state: State[RoverArticleInfo]): State[ArticleInfo] = state.copy()
  implicit def fromArticleInfoState(state: State[ArticleInfo]): State[RoverArticleInfo] = state.copy()

  // Warning: if you add fields to ArticleInfo, make sure RoverArticleInfo.seq is incremented when they change
  implicit def toArticleInfo(info: RoverArticleInfo): ArticleInfo = {
    ArticleInfo(
      info.state == ArticleInfoStates.INACTIVE,
      info.seq,
      info.uriId,
      info.kind,
      info.bestVersion,
      info.latestVersion
    )
  }

  def initialize(uriId: Id[NormalizedURI], url: String, kind: ArticleKind[_ <: Article]): RoverArticleInfo = {
    val urlHash = UrlHash.hashUrl(url)
    val newInfo = RoverArticleInfo(uriId = uriId, url = url, urlHash = urlHash, kind = kind.typeCode)
    newInfo.initializeSchedulingPolicy
  }

  def applyFromDbRow(
    id: Option[Id[RoverArticleInfo]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[RoverArticleInfo],
    seq: SequenceNumber[RoverArticleInfo],
    uriId: Id[NormalizedURI],
    url: String,
    urlHash: UrlHash,
    kind: String,
    bestVersion: Option[ArticleVersion],
    latestVersion: Option[ArticleVersion],
    oldestVersion: Option[ArticleVersion],
    lastFetchedAt: Option[DateTime],
    nextFetchAt: Option[DateTime],
    fetchInterval: Option[Duration],
    failureCount: Int,
    failureInfo: Option[String],
    lastFetchingAt: Option[DateTime],
    lastImageProcessingVersion: Option[ArticleVersion],
    lastImageProcessingAt: Option[DateTime],
    imageProcessingRequestedAt: Option[DateTime]): RoverArticleInfo = {
    RoverArticleInfo(id, createdAt, updatedAt, state, seq, uriId, url, urlHash, kind, bestVersion, latestVersion, oldestVersion, lastFetchedAt, nextFetchAt, lastFetchingAt, fetchInterval, failureCount, failureInfo, imageProcessingRequestedAt, lastImageProcessingVersion, lastImageProcessingAt)
  }

  def unapplyToDbRow(info: RoverArticleInfo) = {
    Some((
      info.id,
      info.createdAt,
      info.updatedAt,
      info.state,
      info.seq,
      info.uriId,
      info.url,
      info.urlHash,
      info.kind,
      info.bestVersion,
      info.latestVersion,
      info.oldestVersion,
      info.lastFetchedAt,
      info.nextFetchAt,
      info.fetchInterval,
      info.failureCount,
      info.failureInfo,
      info.lastFetchingAt,
      info.lastImageProcessingVersion,
      info.lastImageProcessingAt,
      info.imageProcessingRequestedAt
    ))
  }
}

