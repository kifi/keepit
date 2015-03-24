package com.keepit.rover.model

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingPlugin, SequencingActor }
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.model._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ DbSequenceAssigner, VersionNumber, State, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier

@ImplementedBy(classOf[ArticleInfoRepoImpl])
trait ArticleInfoRepo extends Repo[ArticleInfo] with SeqNumberFunction[ArticleInfo] {
  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[ArticleInfo]]
  def getByUri(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[ArticleInfo]
  def intern(uriId: Id[NormalizedURI], url: String, kind: ArticleKind[_ <: Article])(implicit session: RWSession): ArticleInfo
}

@Singleton
class ArticleInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  airbrake: AirbrakeNotifier)
    extends DbRepo[ArticleInfo] with ArticleInfoRepo with SeqNumberDbFunction[ArticleInfo] with Logging {

  import db.Driver.simple._

  type RepoImpl = ArticleInfoTable
  class ArticleInfoTable(tag: Tag) extends RepoTable[ArticleInfo](db, tag, "article_info") with SeqNumberColumn[ArticleInfo] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def kind = column[String]("kind", O.NotNull)
    def bestVersionMajor = column[VersionNumber[Article]]("best_version_major", O.Nullable)
    def bestVersionMinor = column[VersionNumber[Article]]("best_version_minor", O.Nullable)
    def latestVersionMajor = column[VersionNumber[Article]]("latest_version_major", O.Nullable)
    def latestVersionMinor = column[VersionNumber[Article]]("latest_version_minor", O.Nullable)
    def lastQueuedAt = column[DateTime]("last_queued_at", O.Nullable)
    def lastFetchedAt = column[DateTime]("last_fetched_at", O.Nullable)
    def nextFetchAt = column[DateTime]("next_fetch_at", O.Nullable)
    def fetchInterval = column[Float]("fetch_interval", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url, kind, bestVersionMajor.?, bestVersionMinor.?, latestVersionMajor.?, latestVersionMinor.?, lastQueuedAt.?, lastFetchedAt.?, nextFetchAt.?, fetchInterval) <> ((ArticleInfo.applyFromDbRow _).tupled, ArticleInfo.unapplyToDbRow _)
  }

  def table(tag: Tag) = new ArticleInfoTable(tag)
  initTable()

  override def deleteCache(model: ArticleInfo)(implicit session: RSession): Unit = {}

  override def invalidateCache(model: ArticleInfo)(implicit session: RSession): Unit = {}

  override def save(model: ArticleInfo)(implicit session: RWSession): ArticleInfo = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  def getByUris(uriIds: Set[Id[NormalizedURI]], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Map[Id[NormalizedURI], Set[ArticleInfo]] = {
    (for (r <- rows if r.uriId.inSet(uriIds) && r.state =!= excludeState.orNull) yield r).list.toSet.groupBy(_.uriId)
  }

  def getByUri(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article], excludeState: Option[State[ArticleInfo]] = Some(ArticleInfoStates.INACTIVE))(implicit session: RSession): Option[ArticleInfo] = {
    (for (r <- rows if r.uriId === uriId && r.kind === kind.typeCode && r.state =!= excludeState.orNull) yield r).firstOption
  }

  def intern(uriId: Id[NormalizedURI], url: String, kind: ArticleKind[_ <: Article])(implicit session: RWSession): ArticleInfo = {
    getByUri(uriId, kind, excludeState = None) match {
      case Some(articleInfo) if articleInfo.isActive => articleInfo
      case Some(inactiveArticleInfo) => {
        val reactivatedInfo = inactiveArticleInfo.clean.copy(url = url, state = ArticleInfoStates.ACTIVE, nextFetchAt = Some(clock.now()))
        save(reactivatedInfo)
      }
      case None => {
        val newInfo = ArticleInfo(uriId = uriId, url = url, kind = kind.typeCode, nextFetchAt = Some(clock.now()))
        save(newInfo)
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