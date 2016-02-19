package com.keepit.rover.model

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingPlugin, SequencingActor }
import com.keepit.rover.article.policy.{FetchSchedulingPolicy, FailureRecoveryPolicy}
import com.keepit.rover.article._
import com.keepit.model._
import com.keepit.rover.sensitivity.{ UriSensitivityKey, UriSensitivityCache }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.core._

import scala.concurrent.duration._
import scala.slick.jdbc.{ PositionedResult, GetResult }
import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[ArticleInfoRepoImpl])
trait ArticleInfoRepo extends Repo[RoverArticleInfo] with SeqNumberFunction[RoverArticleInfo] {
  def getAll(ids: Set[Id[RoverArticleInfo]])(implicit session: RSession): Map[Id[RoverArticleInfo], RoverArticleInfo]
  def getByUrlAndKind[A <: Article](url: String, kind: ArticleKind[A], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[RoverArticleInfo]
  def getByUrl(url: String, excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[RoverArticleInfo]
  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[RoverArticleInfo]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[RoverArticleInfo]
  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[RoverArticleInfo]]
  def getRipeForFetching(limit: Int, fetchingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo]
  def markAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def unmarkAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def updateAfterFetch[A <: Article](url: String, kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit
  def getRipeForImageProcessing(limit: Int, requestedForMoreThan: Duration, imageProcessingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo]
  def markAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def unmarkAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def updateAfterImageProcessing[A <: Article](url: String, kind: ArticleKind[A], version: Option[ArticleVersion])(implicit session: RWSession): Unit
}

@Singleton
class ArticleInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    articleInfoCache: ArticleInfoUriCache,
    articleSummaryCache: RoverArticleSummaryCache,
    uriSensitivityCache: UriSensitivityCache,
    airbrake: AirbrakeNotifier,
    implicit val failurePolicy: FailureRecoveryPolicy) extends DbRepo[RoverArticleInfo] with ArticleInfoRepo with SeqNumberDbFunction[RoverArticleInfo] with Logging {

  import db.Driver.simple._

  private def articleVersionFromDb(versionMajor: Option[VersionNumber[Article]], versionMinor: Option[VersionNumber[Article]]): Option[ArticleVersion] = {
    for {
      major <- versionMajor
      minor <- versionMinor
    } yield ArticleVersion(major, minor)
  }

  private def articleVersionToDb(version: Option[ArticleVersion]): Option[(Option[VersionNumber[Article]], Option[VersionNumber[Article]])] = Some((version.map(_.major), version.map(_.minor)))

  type RepoImpl = ArticleInfoTable
  class ArticleInfoTable(tag: Tag) extends RepoTable[RoverArticleInfo](db, tag, "article_info") with SeqNumberColumn[RoverArticleInfo] {

    def uriId = column[Option[Id[NormalizedURI]]]("uri_id", O.Nullable)
    def url = column[String]("url", O.NotNull)
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def kind = column[String]("kind", O.NotNull)
    def bestVersionMajor = column[Option[VersionNumber[Article]]]("best_version_major", O.Nullable)
    def bestVersionMinor = column[Option[VersionNumber[Article]]]("best_version_minor", O.Nullable)
    def latestVersionMajor = column[Option[VersionNumber[Article]]]("latest_version_major", O.Nullable)
    def latestVersionMinor = column[Option[VersionNumber[Article]]]("latest_version_minor", O.Nullable)
    def oldestVersionMajor = column[Option[VersionNumber[Article]]]("oldest_version_major", O.Nullable)
    def oldestVersionMinor = column[Option[VersionNumber[Article]]]("oldest_version_minor", O.Nullable)
    def lastFetchedAt = column[Option[DateTime]]("last_fetched_at", O.Nullable)
    def nextFetchAt = column[Option[DateTime]]("next_fetch_at", O.Nullable)
    def lastFetchingAt = column[Option[DateTime]]("last_fetching_at", O.Nullable)
    def fetchInterval = column[Option[Duration]]("fetch_interval", O.Nullable)
    def failureCount = column[Int]("failure_count", O.NotNull)
    def failureInfo = column[Option[String]]("failure_info", O.Nullable)
    def imageProcessingRequestedAt = column[Option[DateTime]]("image_processing_requested_at", O.Nullable)
    def lastImageProcessingVersionMajor = column[Option[VersionNumber[Article]]]("last_image_processing_version_major", O.Nullable)
    def lastImageProcessingVersionMinor = column[Option[VersionNumber[Article]]]("last_image_processing_version_minor", O.Nullable)
    def lastImageProcessingAt = column[Option[DateTime]]("last_image_processing_at", O.Nullable)

    def bestVersion = (bestVersionMajor, bestVersionMinor) <> ((articleVersionFromDb _).tupled, articleVersionToDb _)
    def latestVersion = (latestVersionMajor, latestVersionMinor) <> ((articleVersionFromDb _).tupled, articleVersionToDb _)
    def oldestVersion = (oldestVersionMajor, oldestVersionMinor) <> ((articleVersionFromDb _).tupled, articleVersionToDb _)
    def lastImageProcessingVersion = (lastImageProcessingVersionMajor, lastImageProcessingVersionMinor) <> ((articleVersionFromDb _).tupled, articleVersionToDb _)

    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url, urlHash, kind, bestVersion, latestVersion, oldestVersion, lastFetchedAt, nextFetchAt, fetchInterval, failureCount, failureInfo, lastFetchingAt, lastImageProcessingVersion, lastImageProcessingAt, imageProcessingRequestedAt) <> ((RoverArticleInfo.applyFromDbRow _).tupled, RoverArticleInfo.unapplyToDbRow _)
  }

  implicit val getArticleInfoResult = GetResult[RoverArticleInfo] { r: PositionedResult =>

    RoverArticleInfo.applyFromDbRow(
      id = r.<<[Option[Id[RoverArticleInfo]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      state = r.<<[State[RoverArticleInfo]],
      seq = r.<<[SequenceNumber[RoverArticleInfo]],
      uriId = r.<<[Option[Id[NormalizedURI]]],
      url = r.<<[String],
      urlHash = r.<<[UrlHash],
      kind = r.<<[String],
      bestVersion = articleVersionFromDb(r.<<[Option[VersionNumber[Article]]], r.<<[Option[VersionNumber[Article]]]),
      latestVersion = articleVersionFromDb(r.<<[Option[VersionNumber[Article]]], r.<<[Option[VersionNumber[Article]]]),
      oldestVersion = articleVersionFromDb(r.<<[Option[VersionNumber[Article]]], r.<<[Option[VersionNumber[Article]]]),
      lastFetchedAt = r.<<[Option[DateTime]],
      nextFetchAt = r.<<[Option[DateTime]],
      fetchInterval = r.<<[Option[Duration]],
      failureCount = r.<<[Int],
      failureInfo = r.<<[Option[String]],
      lastFetchingAt = r.<<[Option[DateTime]],
      lastImageProcessingVersion = articleVersionFromDb(r.<<[Option[VersionNumber[Article]]], r.<<[Option[VersionNumber[Article]]]),
      lastImageProcessingAt = r.<<[Option[DateTime]],
      imageProcessingRequestedAt = r.<<[Option[DateTime]]
    )
  }

  def table(tag: Tag) = new ArticleInfoTable(tag)
  initTable()

  override def deleteCache(model: RoverArticleInfo)(implicit session: RSession): Unit = {
    model.uriId.foreach { uriId =>
      articleInfoCache.remove(ArticleInfoUriKey(uriId))
      articleSummaryCache.remove(RoverArticleSummaryKey(uriId, model.articleKind))
      uriSensitivityCache.remove(UriSensitivityKey(uriId))
    }
  }

  override def invalidateCache(model: RoverArticleInfo)(implicit session: RSession): Unit = {}

  override def save(model: RoverArticleInfo)(implicit session: RWSession): RoverArticleInfo = {
    super.save(model.copy(seq = deferredSeqNum())) tap deleteCache
  }

  // Dangerous: this does *not* increment the sequence number nor invalidate caches
  def saveSilently(model: RoverArticleInfo)(implicit session: RWSession): RoverArticleInfo = {
    super.save(model)
  }

  def getAll(ids: Set[Id[RoverArticleInfo]])(implicit session: RSession): Map[Id[RoverArticleInfo], RoverArticleInfo] = {
    (for (r <- rows if r.id.inSet(ids)) yield (r.id, r)).toMap
  }

  def getByUrlAndKind[A <: Article](url: String, kind: ArticleKind[A], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[RoverArticleInfo] = {
    val urlHash = UrlHash.hashUrl(url)
    (for (r <- rows if r.urlHash === urlHash && r.url === url && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).firstOption
  }

  def getByUrl(url: String, excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[RoverArticleInfo] = {
    val urlHash = UrlHash.hashUrl(url)
    (for (r <- rows if r.urlHash === urlHash && r.url === url && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[RoverArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).firstOption
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[RoverArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[RoverArticleInfo]] = {
    val existingByUriId = (for (r <- rows if r.uriId.inSet(uriIds) && r.state =!= excludeState.orNull) yield r).list.toSet[RoverArticleInfo].groupBy(_.uriId.get)
    val missingUriIds = uriIds -- existingByUriId.keySet
    existingByUriId ++ missingUriIds.map(_ -> Set.empty[RoverArticleInfo])
  }

  def getRipeForFetching(limit: Int, fetchingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val now = clock.now()
    val lastFetchingTooLongAgo = now minusSeconds fetchingForMoreThan.toSeconds.toInt

    val q = sql"""
      SELECT *
      FROM `article_info`
      WHERE `state` = 'active' AND `next_fetch_at` < $now AND (`last_fetching_at` IS NULL OR `last_fetching_at` < $lastFetchingTooLongAgo) ORDER BY `last_fetched_at` IS NULL DESC , `next_fetch_at` ASC LIMIT $limit;
    """
    q.as[RoverArticleInfo].list
  }

  def markAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastFetchingAt(ids, Some(clock.now()))
  def unmarkAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastFetchingAt(ids, None)

  private def updateLastFetchingAt(ids: Seq[Id[RoverArticleInfo]], lastFetchingAt: Option[DateTime])(implicit session: RWSession): Unit = {
    (for (r <- rows if r.id.inSet(ids.toSet)) yield r.lastFetchingAt).update(lastFetchingAt)
  }

  def updateAfterFetch[A <: Article](url: String, kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit = {
    getByUrlAndKind(url, kind).foreach { articleInfo =>
      val withFetchComplete = articleInfo.withLatestFetchComplete
      fetched match {
        case Failure(error) => saveSilently(withFetchComplete.withFailure(error))
        case Success(None) => saveSilently(withFetchComplete.withoutChange)
        case Success(Some(articleVersion)) => {
          save(withFetchComplete.withLatestArticle(articleVersion))
          onLatestArticle(url, kind)
        }
      }
    }
  }

  private def onLatestArticle[A <: Article](url: String, kind: ArticleKind[A])(implicit session: RWSession): Unit = {
    if (kind != EmbedlyArticle) {
      refresh(url, EmbedlyArticle, ifLastFetchOlderThan = FetchSchedulingPolicy.embedlyRefreshOnContentChangeIfOlderThan)
    }
  }

  private def refresh[A <: Article](url: String, kind: ArticleKind[A], ifLastFetchOlderThan: Duration)(implicit session: RWSession): Unit = {
    getByUrlAndKind(url, kind).foreach { articleInfo =>
      val now = clock.now()
      val fetchedRecencyLimit = now.minusSeconds(ifLastFetchOlderThan.toSeconds.toInt)
      val shouldRefreshNow = !(articleInfo.nextFetchAt.exists(_ isBefore now) || articleInfo.lastFetchedAt.exists(_ isAfter fetchedRecencyLimit))
      if (shouldRefreshNow) {
        saveSilently(articleInfo.copy(nextFetchAt = Some(now)))
      }
    }
  }

  def getRipeForImageProcessing(limit: Int, requestedForMoreThan: Duration, imageProcessingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val now = clock.now()
    val lastImageProcessingTooLongAgo = now minusSeconds imageProcessingForMoreThan.toSeconds.toInt
    val imageProcessingRequestedLongEnoughAgo = now minusSeconds requestedForMoreThan.toSeconds.toInt

    val q = sql"""
          (select * from article_info
          where state = '#${ArticleInfoStates.ACTIVE}' and last_image_processing_at is not null and last_image_processing_at < $lastImageProcessingTooLongAgo
          order by last_image_processing_at limit $limit)
      union
          (select * from article_info
          where state = '#${ArticleInfoStates.ACTIVE}' and last_image_processing_at is null and image_processing_requested_at is not null and image_processing_requested_at < $imageProcessingRequestedLongEnoughAgo
          order by image_processing_requested_at limit $limit)
       limit $limit
    """

    q.as[RoverArticleInfo].list
  }

  def markAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastImageProcessingAt(ids, Some(clock.now()))
  def unmarkAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastImageProcessingAt(ids, None)

  private def updateLastImageProcessingAt(ids: Seq[Id[RoverArticleInfo]], lastImageProcessingAt: Option[DateTime])(implicit session: RWSession): Unit = {
    (for (r <- rows if r.id.inSet(ids.toSet)) yield r.lastImageProcessingAt).update(lastImageProcessingAt)
  }

  def updateAfterImageProcessing[A <: Article](url: String, kind: ArticleKind[A], version: Option[ArticleVersion])(implicit session: RWSession): Unit = {
    getByUrlAndKind(url, kind).foreach { articleInfo =>
      saveSilently(articleInfo.withImageProcessingComplete(version))
    }
  }

}

trait ArticleInfoSequencingPlugin extends SequencingPlugin

class ArticleInfoSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[ArticleInfoSequencingActor],
  override val scheduling: SchedulingProperties) extends ArticleInfoSequencingPlugin

@Singleton
class ArticleInfoSequenceNumberAssigner @Inject() (db: Database, repo: ArticleInfoRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[RoverArticleInfo](db, repo, airbrake)

class ArticleInfoSequencingActor @Inject() (
  assigner: ArticleInfoSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
