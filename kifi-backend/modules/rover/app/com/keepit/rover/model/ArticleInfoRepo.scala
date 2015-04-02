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
  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo]
  def markAsQueued(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def unmarkAsQueued(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit
  def updateAfterFetch[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit
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
    def fetchInterval = column[Option[Duration]]("fetch_interval", O.Nullable)
    def failureCount = column[Int]("failure_count", O.NotNull)
    def failureInfo = column[Option[String]]("failure_info", O.Nullable)
    def lastQueuedAt = column[Option[DateTime]]("last_queued_at", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersionMajor, bestVersionMinor, latestVersionMajor, latestVersionMinor, oldestVersionMajor, oldestVersionMinor, lastFetchedAt, nextFetchAt, fetchInterval, failureCount, failureInfo, lastQueuedAt) <> ((RoverArticleInfo.applyFromDbRow _).tupled, RoverArticleInfo.unapplyToDbRow _)
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
          case Some(articleInfo) if articleInfo.isActive => articleInfo
          case Some(inactiveArticleInfo) => {
            val reactivatedInfo = inactiveArticleInfo.clean.copy(url = url, state = ArticleInfoStates.ACTIVE).initializeSchedulingPolicy
            save(reactivatedInfo)
          }
          case None => {
            val newInfo = RoverArticleInfo(uriId = uriId, url = url, kind = kind.typeCode).initializeSchedulingPolicy
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

  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration)(implicit session: RSession): Seq[RoverArticleInfo] = {
    val ripeRows = {
      val now = clock.now()
      val lastQueuedTooLongAgo = now minusSeconds queuedForMoreThan.toSeconds.toInt
      for (r <- rows if r.state === ArticleInfoStates.ACTIVE && r.nextFetchAt < now && (r.lastQueuedAt.isEmpty || r.lastQueuedAt < lastQueuedTooLongAgo)) yield r
    }
    ripeRows.sortBy(_.nextFetchAt).take(limit).list
  }

  def markAsQueued(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastQueuedAt(ids, Some(clock.now()))

  def unmarkAsQueued(ids: Id[RoverArticleInfo]*)(implicit session: RWSession): Unit = updateLastQueuedAt(ids, None)

  private def updateLastQueuedAt(ids: Seq[Id[RoverArticleInfo]], lastQueuedAt: Option[DateTime])(implicit session: RWSession): Unit = {
    (for (r <- rows if r.id.inSet(ids.toSet)) yield r.lastQueuedAt).update(lastQueuedAt)
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