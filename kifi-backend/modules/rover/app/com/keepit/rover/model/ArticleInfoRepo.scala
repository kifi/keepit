package com.keepit.rover.model

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingPlugin, SequencingActor }
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.model._
import com.keepit.rover.manager.FailureRecoveryPolicy
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ DbSequenceAssigner, VersionNumber, State, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier

import scala.concurrent.duration.Duration
import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[ArticleInfoRepoImpl])
trait ArticleInfoRepo extends Repo[ArticleInfo] with SeqNumberFunction[ArticleInfo] {
  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[ArticleInfo]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[ArticleInfo]
  def internByUri(uriId: Id[NormalizedURI], url: String, kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Map[ArticleKind[_ <: Article], ArticleInfo]
  def deactivateByUri(uriId: Id[NormalizedURI])(implicit session: RWSession): Unit
  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration)(implicit session: RSession): Seq[ArticleInfo]
  def markAsQueued(ids: Id[ArticleInfo]*)(implicit session: RWSession): Unit
  def unmarkAsQueued(ids: Id[ArticleInfo]*)(implicit session: RWSession): Unit
  def updateAfterFetch[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit
}

@Singleton
class ArticleInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier,
    implicit val failurePolicy: FailureRecoveryPolicy) extends DbRepo[ArticleInfo] with ArticleInfoRepo with SeqNumberDbFunction[ArticleInfo] with Logging {

  import db.Driver.simple._

  type RepoImpl = ArticleInfoTable
  class ArticleInfoTable(tag: Tag) extends RepoTable[ArticleInfo](db, tag, "article_info") with SeqNumberColumn[ArticleInfo] {
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

    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersionMajor, bestVersionMinor, latestVersionMajor, latestVersionMinor, oldestVersionMajor, oldestVersionMinor, lastFetchedAt, nextFetchAt, fetchInterval, failureCount, failureInfo, lastQueuedAt) <> ((ArticleInfo.applyFromDbRow _).tupled, ArticleInfo.unapplyToDbRow _)
  }

  def table(tag: Tag) = new ArticleInfoTable(tag)
  initTable()

  override def deleteCache(model: ArticleInfo)(implicit session: RSession): Unit = {}

  override def invalidateCache(model: ArticleInfo)(implicit session: RSession): Unit = {}

  override def save(model: ArticleInfo)(implicit session: RWSession): ArticleInfo = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  // Dangerous: this does *not* increment the sequence number - use only if you want an update *not* to be broadcasted!
  private def saveSilently(model: ArticleInfo)(implicit session: RWSession): ArticleInfo = {
    super.save(model)
  }

  def getByUriAndKind[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[ArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).firstOption
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Set[ArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.state =!= excludeState.orNull) yield r).list.toSet
  }

  def internByUri(uriId: Id[NormalizedURI], url: String, kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Map[ArticleKind[_ <: Article], ArticleInfo] = {
    if (kinds.isEmpty) Map.empty[ArticleKind[_ <: Article], ArticleInfo]
    else {
      val existingByKind: Map[ArticleKind[_ <: Article], ArticleInfo] = getByUri(uriId, excludeState = None).map { info => (info.articleKind -> info) }.toMap
      kinds.map { kind =>
        val savedInfo = existingByKind.get(kind) match {
          case Some(articleInfo) if articleInfo.isActive => articleInfo
          case Some(inactiveArticleInfo) => {
            val reactivatedInfo = inactiveArticleInfo.clean.copy(url = url, state = ArticleInfoStates.ACTIVE).initializeSchedulingPolicy
            save(reactivatedInfo)
          }
          case None => {
            val newInfo = ArticleInfo(uriId = uriId, url = url, kind = kind.typeCode).initializeSchedulingPolicy
            save(newInfo)
          }
        }
        kind -> savedInfo
      }.toMap
    }
  }

  def deactivateByUri(uriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    getByUri(uriId).foreach { info =>
      save(info.copy(state = ArticleInfoStates.INACTIVE))
    }
  }

  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration)(implicit session: RSession): Seq[ArticleInfo] = {
    val ripeRows = {
      val now = clock.now()
      val lastQueuedTooLongAgo = now minusSeconds queuedForMoreThan.toSeconds.toInt
      for (r <- rows if r.state === ArticleInfoStates.ACTIVE && r.nextFetchAt < now && (r.lastQueuedAt.isEmpty || r.lastQueuedAt < lastQueuedTooLongAgo)) yield r
    }
    ripeRows.sortBy(_.nextFetchAt).take(limit).list
  }

  def markAsQueued(ids: Id[ArticleInfo]*)(implicit session: RWSession): Unit = updateLastQueuedAt(ids, Some(clock.now()))

  def unmarkAsQueued(ids: Id[ArticleInfo]*)(implicit session: RWSession): Unit = updateLastQueuedAt(ids, None)

  private def updateLastQueuedAt(ids: Seq[Id[ArticleInfo]], lastQueuedAt: Option[DateTime])(implicit session: RWSession): Unit = {
    (for (r <- rows if r.id.inSet(ids.toSet)) yield r.lastQueuedAt).update(lastQueuedAt)
  }

  // todo(Léo): probably be smarter about this, we always get the Embedly response but we may still want to delay future fetches
  def updateAfterFetch[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], fetched: Try[Option[ArticleVersion]])(implicit session: RWSession): Unit = {
    getByUriAndKind(uriId, kind).foreach { articleInfo =>
      val withFetchComplete = articleInfo.withLatestFetchComplete
      fetched match {
        case Failure(error) => saveSilently(withFetchComplete.withFailure(error))
        case Success(None) => saveSilently(withFetchComplete.withoutChange)
        case Success(Some(articleVersion)) => save(withFetchComplete.withLatestArticle(articleVersion))
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
  extends DbSequenceAssigner[ArticleInfo](db, repo, airbrake)

class ArticleInfoSequencingActor @Inject() (
  assigner: ArticleInfoSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)