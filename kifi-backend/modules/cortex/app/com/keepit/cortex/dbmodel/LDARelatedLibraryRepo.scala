package com.keepit.cortex.dbmodel

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, Database, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.sql.CortexTypeMappers
import com.keepit.model.Library

@ImplementedBy(classOf[LDARelatedLibraryRepoImpl])
trait LDARelatedLibraryRepo extends DbRepo[LDARelatedLibrary] {
  def getRelatedLibraries(sourceId: Id[Library], version: ModelVersion[DenseLDA], excludeState: Option[State[LDARelatedLibrary]])(implicit session: RSession): Seq[LDARelatedLibrary]
  def getIncomingEdges(destId: Id[Library], version: ModelVersion[DenseLDA], excludeState: Option[State[LDARelatedLibrary]])(implicit session: RSession): Seq[LDARelatedLibrary]
  def getNeighborIdsAndWeights(sourceId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[(Id[Library], Float)]
  def getTopNeighborIdsAndWeights(sourceId: Id[Library], version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[(Id[Library], Float)]
}

@Singleton
class LDARelatedLibraryRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[LDARelatedLibrary] with LDARelatedLibraryRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = LDARelatedLibraryTable

  class LDARelatedLibraryTable(tag: Tag) extends RepoTable[LDARelatedLibrary](db, tag, "lda_related_library") {
    def version = column[ModelVersion[DenseLDA]]("version")
    def sourceId = column[Id[Library]]("source_id")
    def destId = column[Id[Library]]("dest_id")
    def weight = column[Float]("weight")
    def * = (id.?, createdAt, updatedAt, version, sourceId, destId, weight, state) <> ((LDARelatedLibrary.apply _).tupled, LDARelatedLibrary.unapply _)
  }

  def table(tag: Tag) = new LDARelatedLibraryTable(tag)
  initTable()

  def deleteCache(model: LDARelatedLibrary)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LDARelatedLibrary)(implicit session: RSession): Unit = {}

  def getRelatedLibraries(sourceId: Id[Library], version: ModelVersion[DenseLDA], excludeState: Option[State[LDARelatedLibrary]])(implicit session: RSession): Seq[LDARelatedLibrary] = {
    (for (r <- rows if r.version === version && r.sourceId === sourceId && r.state =!= excludeState.orNull) yield r).list
  }

  def getIncomingEdges(destId: Id[Library], version: ModelVersion[DenseLDA], excludeState: Option[State[LDARelatedLibrary]])(implicit session: RSession): Seq[LDARelatedLibrary] = {
    (for (r <- rows if r.version === version && r.destId === destId && r.state =!= excludeState.orNull) yield r).list
  }

  def getNeighborIdsAndWeights(sourceId: Id[Library], version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[(Id[Library], Float)] = {
    (for (r <- rows if r.version === version && r.sourceId === sourceId && r.state === LDARelatedLibraryStates.ACTIVE) yield (r.destId, r.weight)).list
  }

  def getTopNeighborIdsAndWeights(sourceId: Id[Library], version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[(Id[Library], Float)] = {
    (for (r <- rows if r.version === version && r.sourceId === sourceId && r.state === LDARelatedLibraryStates.ACTIVE) yield (r.destId, r.weight)).sortBy(_._2.desc).take(limit).list
  }

}
