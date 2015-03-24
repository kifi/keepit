package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.rover.article.{ ArticleFetchRequest, Article, ArticleKind }
import org.joda.time.DateTime

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
    lastQueuedAt: Option[DateTime] = None,
    lastFetchedAt: Option[DateTime] = None,
    nextFetchAt: Option[DateTime] = None,
    fetchInterval: Float = 24.0f) extends ModelWithState[ArticleInfo] with ModelWithSeqNumber[ArticleInfo] with ArticleKeyHolder {
  def withId(id: Id[ArticleInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == ArticleInfoStates.ACTIVE)
  def getFetchRequest[A <: Article](implicit kind: ArticleKind[A]) = ArticleFetchRequest(kind, url, lastFetchedAt, getLatestKey)
  def clean: ArticleInfo = copy(
    bestVersion = None,
    latestVersion = None,
    lastQueuedAt = None,
    lastFetchedAt = None,
    nextFetchAt = None,
    fetchInterval = 24.0f
  )
}

object ArticleInfo {
  implicit def fromArticleInfoSeq(seq: SequenceNumber[ArticleInfo]): SequenceNumber[BasicArticleInfo] = seq.copy()
  implicit def toArticleInfoSeq(seq: SequenceNumber[BasicArticleInfo]): SequenceNumber[ArticleInfo] = seq.copy()
  implicit def fromArticleInfoState(state: State[ArticleInfo]): State[BasicArticleInfo] = state.copy()
  implicit def toArticleInfoState(state: State[BasicArticleInfo]): State[ArticleInfo] = state.copy()
  implicit def toBasicArticleInfo(info: ArticleInfo): BasicArticleInfo = {
    BasicArticleInfo(
      info.state == ArticleInfoStates.INACTIVE,
      info.seq,
      info.uriId,
      info.kind,
      info.bestVersion,
      info.latestVersion,
      info.lastFetchedAt
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
    lastQueuedAt: Option[DateTime],
    lastFetchedAt: Option[DateTime],
    nextFetchAt: Option[DateTime],
    fetchInterval: Float): ArticleInfo = {
    val bestVersion = articleVersionFromDb(bestVersionMajor, bestVersionMinor)
    val latestVersion = articleVersionFromDb(latestVersionMajor, latestVersionMinor)
    ArticleInfo(id, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersion, latestVersion, lastQueuedAt, lastFetchedAt, nextFetchAt, fetchInterval)
  }

  def unapplyToDbRow(info: ArticleInfo) = {
    val (bestVersionMajor, bestVersionMinor) = articleVersionToDb(info.bestVersion)
    val (latestVersionMajor, latestVersionMinor) = articleVersionToDb(info.latestVersion)
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
      info.lastQueuedAt,
      info.lastFetchedAt,
      info.nextFetchAt,
      info.fetchInterval
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

