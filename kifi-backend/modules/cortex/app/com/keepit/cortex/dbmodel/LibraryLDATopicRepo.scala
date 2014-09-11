package com.keepit.cortex.dbmodel

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ DenseLDA }
import com.keepit.cortex.sql.CortexTypeMappers
import com.keepit.model.Library
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }

@ImplementedBy(classOf[LibraryLDATopicRepoImpl])
trait LibraryLDATopicRepo extends DbRepo[LibraryLDATopic] {
  def getByLibraryId(libId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LibraryLDATopic]
  def getActiveByLibraryId(libId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LibraryLDATopic]
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
    def * = (id.?, createdAt, updatedAt, libraryId, version, numOfEvidence, topic.?, state) <> ((LibraryLDATopic.apply _).tupled, LibraryLDATopic.unapply _)
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
}
