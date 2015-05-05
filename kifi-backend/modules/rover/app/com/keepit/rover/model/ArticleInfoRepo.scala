package com.keepit.rover.model

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingPlugin, SequencingActor }
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.model._
import com.keepit.rover.commanders.{ ArticleInfoUriKey, ArticleInfoUriCache }
import com.keepit.rover.manager.FailureRecoveryPolicy
import com.keepit.rover.sensitivity.{ UriSensitivityKey, UriSensitivityCache }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ DbSequenceAssigner, VersionNumber, State, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.core._

import scala.concurrent.duration.Duration
import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[ArticleInfoRepoImpl])
trait ArticleInfoRepo extends Repo[RoverArticleInfo] with SeqNumberFunction[RoverArticleInfo] {
  def getAll(ids: Set[Id[RoverArticleInfo]])(implicit session: RSession): Map[Id[RoverArticleInfo], RoverArticleInfo]
  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[RoverArticleInfo]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[RoverArticleInfo]
  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[RoverArticleInfo]]
  def internByUri(uriId: Id[NormalizedURI], url: String, kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Map[ArticleKind[_ <: Article], RoverArticleInfo]
  def deactivateByUriAndKinds(uriId: Id[NormalizedURI], kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Unit
  def getRipeForFetching(limit: Int, fetchingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo]
  def markAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def unmarkAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def updateAfterFetch[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit
  def getRipeForImageProcessing(limit: Int, requestedForMoreThan: Duration, imageProcessingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo]
  def markAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def unmarkAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def updateAfterImageProcessing[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], version: Option[ArticleVersion])(implicit session: RWSession): Unit
}

@Singleton
class ArticleInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    articleInfoCache: ArticleInfoUriCache,
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

    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
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

    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersion, latestVersion, oldestVersion, lastFetchedAt, nextFetchAt, lastFetchingAt, fetchInterval, failureCount, failureInfo, imageProcessingRequestedAt, lastImageProcessingVersion, lastImageProcessingAt) <> ((RoverArticleInfo.applyFromDbRow _).tupled, RoverArticleInfo.unapplyToDbRow _)
  }

  def table(tag: Tag) = new ArticleInfoTable(tag)
  initTable()

  override def deleteCache(model: RoverArticleInfo)(implicit session: RSession): Unit = {
    articleInfoCache.remove(ArticleInfoUriKey(model.uriId))
    uriSensitivityCache.remove(UriSensitivityKey(model.uriId))
  }

  override def invalidateCache(model: RoverArticleInfo)(implicit session: RSession): Unit = {}

  override def save(model: RoverArticleInfo)(implicit session: RWSession): RoverArticleInfo = {
    super.save(model.copy(seq = deferredSeqNum())) tap deleteCache
  }

  // Dangerous: this does *not* increment the sequence number nor invalidate caches
  private def saveSilently(model: RoverArticleInfo)(implicit session: RWSession): RoverArticleInfo = {
    super.save(model)
  }

  def getAll(ids: Set[Id[RoverArticleInfo]])(implicit session: RSession): Map[Id[RoverArticleInfo], RoverArticleInfo] = {
    (for (r <- rows if r.id.inSet(ids)) yield (r.id, r)).toMap
  }

  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[RoverArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).firstOption
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[RoverArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[RoverArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[RoverArticleInfo]] = {
    val existingByUriId = (for (r <- rows if r.uriId.inSet(uriIds) && r.state =!= excludeState.orNull) yield r).list.toSet[RoverArticleInfo].groupBy(_.uriId)
    val missingUriIds = uriIds -- existingByUriId.keySet
    existingByUriId ++ missingUriIds.map(_ -> Set.empty[RoverArticleInfo])
  }

  def internByUri(uriId: Id[NormalizedURI], url: String, kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Map[ArticleKind[_ <: Article], RoverArticleInfo] = {
    if (kinds.isEmpty) Map.empty[ArticleKind[_ <: Article], RoverArticleInfo]
    else {
      val existingByKind: Map[ArticleKind[_ <: Article], RoverArticleInfo] = getByUri(uriId, excludeState = None).map { info => (info.articleKind -> info) }.toMap
      kinds.map { kind =>
        val savedInfo = existingByKind.get(kind) match {
          case Some(articleInfo) if articleInfo.isActive && articleInfo.url == url => articleInfo
          case Some(inactiveArticleInfo) if !inactiveArticleInfo.isActive => {
            val reactivatedInfo = inactiveArticleInfo.clean.copy(url = url, state = ArticleInfoStates.ACTIVE).initializeSchedulingPolicy
            save(reactivatedInfo)
          }
          case Some(invalidArticleInfo) if invalidArticleInfo.url != url => {
            airbrake.notify(s"Fixed ArticleInfo $kind for uri $uriId with inconsistent url: expected $url, had ${invalidArticleInfo.url}")
            val validArticleInfo = invalidArticleInfo.copy(url = url)
            save(validArticleInfo)
          }
          case None => {
            val newInfo = RoverArticleInfo.initialize(uriId, url, kind)
            save(newInfo)
          }
        }
        kind -> savedInfo
      }.toMap
    }
  }

  def deactivateByUriAndKinds(uriId: Id[NormalizedURI], kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Unit = {
    if (kinds.nonEmpty) {
      getByUri(uriId).foreach { info =>
        if (kinds.contains(info.articleKind)) {
          save(info.copy(state = ArticleInfoStates.INACTIVE))
        }
      }
    }
  }

  def getRipeForFetching(limit: Int, fetchingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo] = {
    val ripeRows = {
      val now = clock.now()
      val lastFetchingTooLongAgo = now minusSeconds fetchingForMoreThan.toSeconds.toInt
      for (r <- rows if r.state === ArticleInfoStates.ACTIVE && r.nextFetchAt < now && (r.lastFetchingAt.isEmpty || r.lastFetchingAt < lastFetchingTooLongAgo)) yield r
    }
    ripeRows.sortBy(_.nextFetchAt).take(limit).list
  }

  def markAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastFetchingAt(ids, Some(clock.now()))
  def unmarkAsFetching(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastFetchingAt(ids, None)

  private def updateLastFetchingAt(ids: Seq[Id[RoverArticleInfo]], lastFetchingAt: Option[DateTime])(implicit session: RWSession): Unit = {
    (for (r <- rows if r.id.inSet(ids.toSet)) yield r.lastFetchingAt).update(lastFetchingAt)
  }

  // todo(Léo): probably be smarter about this, we always get the Embedly response but we may still want to delay future fetches
  def updateAfterFetch[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit = {
    getByUriAndKind(uriId, kind).foreach { articleInfo =>
      val withFetchComplete = articleInfo.withLatestFetchComplete
      fetched match {
        case Failure(error) => saveSilently(withFetchComplete.withFailure(error))
        case Success(None) => saveSilently(withFetchComplete.withoutChange)
        case Success(Some(articleVersion)) => {
          save(withFetchComplete.withLatestArticle(articleVersion))
        }
      }
    }
  }

  def getRipeForImageProcessing(limit: Int, requestedForMoreThan: Duration, imageProcessingForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo] = {
    val ripeRows = {
      val now = clock.now()
      val imageProcessingRequestedLongEnoughAgo = now minusSeconds requestedForMoreThan.toSeconds.toInt // due
      val lastImageProcessingTooLongAgo = now minusSeconds imageProcessingForMoreThan.toSeconds.toInt // stale

      for (
        r <- rows if {
          r.state === ArticleInfoStates.ACTIVE && {
            (r.lastImageProcessingAt.isDefined && r.lastImageProcessingAt < lastImageProcessingTooLongAgo) || {
              (r.lastImageProcessingAt.isEmpty && r.imageProcessingRequestedAt.isDefined && r.imageProcessingRequestedAt < imageProcessingRequestedLongEnoughAgo)
            }
          }
        }
      ) yield r
    }

    log.info(s"ArticleInfoRepo.getRipeForImageProcessing SQL Statement:\n${ripeRows.sortBy(_.lastFetchedAt).take(limit).selectStatement}")

    ripeRows.sortBy(_.imageProcessingRequestedAt).take(limit).list
  }

  def markAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastImageProcessingAt(ids, Some(clock.now()))
  def unmarkAsImageProcessing(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastImageProcessingAt(ids, None)

  private def updateLastImageProcessingAt(ids: Seq[Id[RoverArticleInfo]], lastImageProcessingAt: Option[DateTime])(implicit session: RWSession): Unit = {
    (for (r <- rows if r.id.inSet(ids.toSet)) yield r.lastImageProcessingAt).update(lastImageProcessingAt)
  }

  def updateAfterImageProcessing[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], version: Option[ArticleVersion])(implicit session: RWSession): Unit = {
    getByUriAndKind(uriId, kind).foreach { articleInfo =>
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