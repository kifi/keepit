package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.rover.article.{ ArticleFetchRequest, Article, ArticleKind }
import com.keepit.rover.manager.{ FailureRecoveryPolicy, FetchSchedulingPolicy }
import org.joda.time.DateTime
import scala.concurrent.duration.Duration

object ArticleInfoStates extends States[ArticleInfo]

case class ArticleInfo(
    id: Option[Id[ArticleInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ArticleInfo] = ArticleInfoStates.ACTIVE,
    seq: SequenceNumber[ArticleInfo] = SequenceNumber.ZERO,
    uriId: Id[NormalizedURI],
    url: String,
    kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
    bestVersion: Option[ArticleVersion] = None,
    latestVersion: Option[ArticleVersion] = None,
    oldestVersion: Option[ArticleVersion] = None,
    lastFetchedAt: Option[DateTime] = None,
    nextFetchAt: Option[DateTime] = None,
    fetchInterval: Option[Duration] = None,
    failureCount: Int = 0,
    failureInfo: Option[String] = None,
    lastQueuedAt: Option[DateTime] = None) extends ModelWithState[ArticleInfo] with ModelWithSeqNumber[ArticleInfo] with ArticleKeyHolder {
  def withId(id: Id[ArticleInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == ArticleInfoStates.ACTIVE)

  def getFetchRequest[A <: Article](implicit kind: ArticleKind[A]) = ArticleFetchRequest(kind, url, lastFetchedAt, getLatestKey)

  def clean: ArticleInfo = copy(
    bestVersion = None,
    latestVersion = None,
    oldestVersion = None,
    lastFetchedAt = None,
    nextFetchAt = None,
    fetchInterval = None,
    failureCount = 0,
    failureInfo = None,
    lastQueuedAt = None
  )

  private def schedulingPolicy = FetchSchedulingPolicy(articleKind)

  def initializeSchedulingPolicy: ArticleInfo = copy(
    nextFetchAt = Some(currentDateTime),
    fetchInterval = Some(schedulingPolicy.initialInterval)
  )

  def withLatestFetchComplete: ArticleInfo = copy(
    lastFetchedAt = Some(currentDateTime),
    lastQueuedAt = None
  )

  def withFailure(error: Throwable)(implicit recoveryPolicy: FailureRecoveryPolicy): ArticleInfo = {
    val updatedFailureCount = failureCount + 1
    val updatedNextFetchAt = {
      if (recoveryPolicy.shouldRetry(url, error, updatedFailureCount)) {
        Some(schedulingPolicy.nextFetchAfterFailure(updatedFailureCount))
      } else None
    }
    copy(
      nextFetchAt = updatedNextFetchAt,
      failureCount = updatedFailureCount,
      failureInfo = Some(error.toString)
    )
  }

  def withLatestArticle(version: ArticleVersion): ArticleInfo = {
    val decreasedFetchInterval = fetchInterval.map(schedulingPolicy.decreaseInterval)
    copy(
      nextFetchAt = decreasedFetchInterval.map(schedulingPolicy.nextFetchAfterSuccess),
      fetchInterval = decreasedFetchInterval,
      latestVersion = Some(version),
      oldestVersion = oldestVersion orElse Some(version),
      failureCount = 0,
      failureInfo = None
    )
  }

  def withoutChange: ArticleInfo = {
    val increasedFetchInterval = fetchInterval.map(schedulingPolicy.increaseInterval)
    copy(
      nextFetchAt = increasedFetchInterval.map(schedulingPolicy.nextFetchAfterSuccess),
      fetchInterval = increasedFetchInterval,
      failureCount = 0,
      failureInfo = None
    )
  }
}

object ArticleInfo {
  implicit def fromArticleInfoSeq(seq: SequenceNumber[ArticleInfo]): SequenceNumber[BasicArticleInfo] = seq.copy()
  implicit def toArticleInfoSeq(seq: SequenceNumber[BasicArticleInfo]): SequenceNumber[ArticleInfo] = seq.copy()
  implicit def fromArticleInfoState(state: State[ArticleInfo]): State[BasicArticleInfo] = state.copy()
  implicit def toArticleInfoState(state: State[BasicArticleInfo]): State[ArticleInfo] = state.copy()

  // Warning: if you add fields to BasicArticleInfo, make sure ArticleInfo.seq is incremented when they change
  implicit def toBasicArticleInfo(info: ArticleInfo): BasicArticleInfo = {
    BasicArticleInfo(
      info.state == ArticleInfoStates.INACTIVE,
      info.seq,
      info.uriId,
      info.kind,
      info.bestVersion,
      info.latestVersion
    )
  }

  def applyFromDbRow(
    id: Option[Id[ArticleInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ArticleInfo] = ArticleInfoStates.ACTIVE,
    seq: SequenceNumber[ArticleInfo] = SequenceNumber.ZERO,
    uriId: Id[NormalizedURI],
    url: String,
    kind: String,
    bestVersionMajor: Option[VersionNumber[Article]],
    bestVersionMinor: Option[VersionNumber[Article]],
    latestVersionMajor: Option[VersionNumber[Article]],
    latestVersionMinor: Option[VersionNumber[Article]],
    oldestVersionMajor: Option[VersionNumber[Article]],
    oldestVersionMinor: Option[VersionNumber[Article]],
    lastFetchedAt: Option[DateTime],
    nextFetchAt: Option[DateTime],
    fetchInterval: Option[Duration],
    failureCount: Int,
    failureInfo: Option[String],
    lastQueuedAt: Option[DateTime]): ArticleInfo = {
    val bestVersion = articleVersionFromDb(bestVersionMajor, bestVersionMinor)
    val latestVersion = articleVersionFromDb(latestVersionMajor, latestVersionMinor)
    val oldestVersion = articleVersionFromDb(oldestVersionMajor, oldestVersionMinor)
    ArticleInfo(id, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersion, latestVersion, oldestVersion, lastFetchedAt, nextFetchAt, fetchInterval, failureCount, failureInfo, lastQueuedAt)
  }

  def unapplyToDbRow(info: ArticleInfo) = {
    val (bestVersionMajor, bestVersionMinor) = articleVersionToDb(info.bestVersion)
    val (latestVersionMajor, latestVersionMinor) = articleVersionToDb(info.latestVersion)
    val (oldestVersionMajor, oldestVersionMinor) = articleVersionToDb(info.oldestVersion)
    Some(
      info.id,
      info.createdAt,
      info.updatedAt,
      info.state,
      info.seq,
      info.uriId,
      info.url,
      info.kind,
      bestVersionMajor,
      bestVersionMinor,
      latestVersionMajor,
      latestVersionMinor,
      oldestVersionMajor,
      oldestVersionMinor,
      info.lastFetchedAt,
      info.nextFetchAt,
      info.fetchInterval,
      info.failureCount,
      info.failureInfo,
      info.lastQueuedAt
    )
  }

  private def articleVersionFromDb(versionMajor: Option[VersionNumber[Article]], versionMinor: Option[VersionNumber[Article]]) = {
    for {
      major <- versionMajor
      minor <- versionMinor
    } yield ArticleVersion(major, minor)
  }

  private def articleVersionToDb(version: Option[ArticleVersion]) = (version.map(_.major), version.map(_.minor))
}

