package com.keepit.cortex.dbmodel

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.cortex.sql.CortexTypeMappers
import com.keepit.model.{ User, Library }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import org.joda.time.DateTime

import scala.slick.jdbc.{ GetResult, StaticQuery }

@ImplementedBy(classOf[LibraryLDATopicRepoImpl])
trait LibraryLDATopicRepo extends DbRepo[LibraryLDATopic] {
  def getByLibraryId(libId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LibraryLDATopic]
  def getActiveByLibraryId(libId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LibraryLDATopic]
  def getActiveByLibraryIds(libIds: Seq[Id[Library]], version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[LibraryLDATopic]
  def getUserFollowedLibraryFeatures(userId: Id[User], version: ModelVersion[DenseLDA], minEvidence: Int = 5)(implicit session: RSession): Seq[LibraryTopicMean]
  def getLibraryByTopics(firstTopic: LDATopic, secondTopic: Option[LDATopic] = None, thirdTopic: Option[LDATopic] = None, version: ModelVersion[DenseLDA], minKeeps: Int = 5, limit: Int)(implicit session: RSession): Seq[LibraryLDATopic]
  def getAllActiveByVersion(version: ModelVersion[DenseLDA], minEvidence: Int = 5)(implicit session: RSession): Seq[LibraryLDATopic]
  def getRecentUpdated(version: ModelVersion[DenseLDA], since: DateTime)(implicit session: RSession): Seq[LibraryLDATopic]
}

@Singleton
class LibraryLDATopicRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[LibraryLDATopic] with LibraryLDATopicRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = LibraryLDATopicTable

  class LibraryLDATopicTable(tag: Tag) extends RepoTable[LibraryLDATopic](db, tag, "library_lda_topic") {
    def libraryId = column[Id[Library]]("library_id")
    def version = column[ModelVersion[DenseLDA]]("version")
    def numOfEvidence = column[Int]("num_of_evidence")
    def topic = column[LibraryTopicMean]("topic", O.Nullable)
    def firstTopic = column[LDATopic]("first_topic", O.Nullable)
    def secondTopic = column[LDATopic]("second_topic", O.Nullable)
    def thirdTopic = column[LDATopic]("third_topic", O.Nullable)
    def firstTopicScore = column[Float]("first_topic_score", O.Nullable)
    def entropy = column[Float]("entropy", O.Nullable)
    def * = (id.?, createdAt, updatedAt, libraryId, version, numOfEvidence, topic.?, state, firstTopic.?, secondTopic.?, thirdTopic.?, firstTopicScore.?, entropy.?) <> ((LibraryLDATopic.apply _).tupled, LibraryLDATopic.unapply _)
  }

  def table(tag: Tag) = new LibraryLDATopicTable(tag)
  initTable()

  def deleteCache(model: LibraryLDATopic)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LibraryLDATopic)(implicit session: RSession): Unit = {}

  def getByLibraryId(libId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LibraryLDATopic] = {
    (for { r <- rows if r.libraryId === libId && r.version === version } yield r).firstOption
  }

  def getActiveByLibraryId(libId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LibraryLDATopic] = {
    (for { r <- rows if r.libraryId === libId && r.version === version && r.state === LibraryLDATopicStates.ACTIVE } yield r).firstOption
  }

  def getActiveByLibraryIds(libIds: Seq[Id[Library]], version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[LibraryLDATopic] = {
    (for { r <- rows if r.libraryId.inSet(libIds) && r.version === version && r.state === LibraryLDATopicStates.ACTIVE } yield r).list
  }

  def getUserFollowedLibraryFeatures(userId: Id[User], version: ModelVersion[DenseLDA], minEvidence: Int = 5)(implicit session: RSession): Seq[LibraryTopicMean] = {
    import StaticQuery.interpolation
    implicit val getLibraryFeature = GetResult(r => libraryTopicMeanMapper.nextValue(r))
    val q =
      sql"""select topic from
           library_lda_topic as tp inner join cortex_library_membership as mem
           on tp.library_id = mem.library_id
           where mem.user_id = ${userId.id} and mem.state = 'active'
           and tp.version = ${version.version} and tp.state = 'active' and tp.num_of_evidence >= ${minEvidence} """
    q.as[LibraryTopicMean].list
  }

  def getLibraryByTopics(firstTopic: LDATopic, secondTopic: Option[LDATopic] = None, thirdTopic: Option[LDATopic] = None, version: ModelVersion[DenseLDA], minKeeps: Int = 5, limit: Int)(implicit session: RSession): Seq[LibraryLDATopic] = {
    assume(!(secondTopic.isEmpty && thirdTopic.isDefined), "when specify third topic, must specify second topic as well")

    if (thirdTopic.isDefined) {
      (for { r <- rows if r.firstTopic === firstTopic && r.secondTopic === secondTopic.get && r.thirdTopic === thirdTopic.get && r.version === version && r.numOfEvidence >= minKeeps && r.state === LibraryLDATopicStates.ACTIVE } yield r).sortBy(_.updatedAt.desc).list.take(limit)
    } else if (secondTopic.isDefined) {
      (for { r <- rows if r.firstTopic === firstTopic && r.secondTopic === secondTopic.get && r.version === version && r.numOfEvidence >= minKeeps && r.state === LibraryLDATopicStates.ACTIVE } yield r).sortBy(_.updatedAt.desc).list.take(limit)
    } else {
      (for { r <- rows if r.firstTopic === firstTopic && r.version === version && r.numOfEvidence >= minKeeps && r.state === LibraryLDATopicStates.ACTIVE } yield r).sortBy(_.updatedAt.desc).list.take(limit)
    }
  }

  def getAllActiveByVersion(version: ModelVersion[DenseLDA], minEvidence: Int = 5)(implicit session: RSession): Seq[LibraryLDATopic] = {
    (for { r <- rows if r.version === version && r.state === LibraryLDATopicStates.ACTIVE && r.numOfEvidence >= minEvidence } yield r).list
  }

  def getRecentUpdated(version: ModelVersion[DenseLDA], since: DateTime)(implicit session: RSession): Seq[LibraryLDATopic] = {
    (for { r <- rows if r.version === version && r.updatedAt > since } yield r).list
  }
}
