package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }
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
    major: Option[VersionNumber[Article]] = None,
    minor: Option[VersionNumber[Article]] = None,
    lastQueuedAt: Option[DateTime] = None,
    lastFetchedAt: Option[DateTime] = None,
    nextFetchAt: Option[DateTime] = None,
    fetchInterval: Double = 24.0d) extends ModelWithState[ArticleInfo] with ModelWithSeqNumber[ArticleInfo] {
  def withId(id: Id[ArticleInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  val articleKind = ArticleKind.byTypeCode(kind)
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
      info.major,
      info.minor,
      info.lastFetchedAt
    )
  }
}

import com.google.inject.{ Singleton, ImplementedBy }
import com.google.inject.Inject
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import org.joda.time.DateTime

@ImplementedBy(classOf[ArticleInfoRepoImpl])
trait ArticleInfoRepo extends Repo[ArticleInfo] with SeqNumberFunction[ArticleInfo]

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
    def major = column[VersionNumber[Article]]("major", O.Nullable)
    def minor = column[VersionNumber[Article]]("minor", O.Nullable)
    def lastQueuedAt = column[DateTime]("last_queued_at", O.Nullable)
    def lastFetchedAt = column[DateTime]("last_fetched_at", O.Nullable)
    def nextFetchAt = column[DateTime]("next_fetch_at", O.Nullable)
    def fetchInterval = column[Double]("fetch_interval", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url, kind, major.?, minor.?, lastQueuedAt.?, lastFetchedAt.?, nextFetchAt.?, fetchInterval) <> ((ArticleInfo.apply _).tupled, ArticleInfo.unapply _)
  }

  def table(tag: Tag) = new ArticleInfoTable(tag)
  initTable()

  override def deleteCache(model: ArticleInfo)(implicit session: RSession): Unit = {}

  override def invalidateCache(model: ArticleInfo)(implicit session: RSession): Unit = {}

  override def save(model: ArticleInfo)(implicit session: RWSession): ArticleInfo = {
    super.save(model.copy(seq = deferredSeqNum()))
  }
}
