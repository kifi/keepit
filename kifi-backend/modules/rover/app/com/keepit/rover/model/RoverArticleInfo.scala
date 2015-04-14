package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.net.URI
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.rover.article.{ ArticleKind, ArticleFetchRequest, Article }
import com.keepit.rover.manager.{ FailureRecoveryPolicy, FetchSchedulingPolicy }
import com.keepit.rover.model.RoverArticleInfo._
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
    kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
    bestVersion: Option[ArticleVersion] = None,
    latestVersion: Option[ArticleVersion] = None,
    oldestVersion: Option[ArticleVersion] = None,
    lastFetchedAt: Option[DateTime] = None,
    nextFetchAt: Option[DateTime] = None,
    fetchInterval: Option[Duration] = None,
    failureCount: Int = 0,
    failureInfo: Option[String] = None,
    lastQueuedAt: Option[DateTime] = None) extends ModelWithState[RoverArticleInfo] with ModelWithSeqNumber[RoverArticleInfo] with ArticleInfoHolder with ArticleKindHolder {

  def withId(id: Id[RoverArticleInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == ArticleInfoStates.ACTIVE)
  def shouldFetch = isActive && lastQueuedAt.isDefined

  def getFetchRequest: ArticleFetchRequest[A] = ArticleFetchRequest(articleKind, url, lastFetchedAt, getLatestKey)

  def clean: RoverArticleInfo = copy(
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

  def initializeSchedulingPolicy: RoverArticleInfo = copy(
    nextFetchAt = Some(currentDateTime),
    fetchInterval = Some(schedulingPolicy.initialInterval)
  )

  def withLatestFetchComplete: RoverArticleInfo = copy(
    lastFetchedAt = Some(currentDateTime),
    lastQueuedAt = None
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
      failureInfo = None
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
    val newInfo = RoverArticleInfo(uriId = uriId, url = url, kind = kind.typeCode)
    newInfo.initializeSchedulingPolicy
  }

  def applyFromDbRow(
    id: Option[Id[RoverArticleInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverArticleInfo] = ArticleInfoStates.ACTIVE,
    seq: SequenceNumber[RoverArticleInfo] = SequenceNumber.ZERO,
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
    lastQueuedAt: Option[DateTime]): RoverArticleInfo = {
    val bestVersion = articleVersionFromDb(bestVersionMajor, bestVersionMinor)
    val latestVersion = articleVersionFromDb(latestVersionMajor, latestVersionMinor)
    val oldestVersion = articleVersionFromDb(oldestVersionMajor, oldestVersionMinor)
    RoverArticleInfo(id, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersion, latestVersion, oldestVersion, lastFetchedAt, nextFetchAt, fetchInterval, failureCount, failureInfo, lastQueuedAt)
  }

  def unapplyToDbRow(info: RoverArticleInfo) = {
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

