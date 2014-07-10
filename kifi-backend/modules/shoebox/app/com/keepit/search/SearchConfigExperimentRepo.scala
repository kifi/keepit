package com.keepit.search

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import org.joda.time.DateTime
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

@ImplementedBy(classOf[SearchConfigExperimentRepoImpl])
trait SearchConfigExperimentRepo extends Repo[SearchConfigExperiment] {
  def getActive()(implicit session: RSession): Seq[SearchConfigExperiment]
  def getNotInactive()(implicit session: RSession): Seq[SearchConfigExperiment]
}

@Singleton
class SearchConfigExperimentRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val cache: ActiveExperimentsCache) extends DbRepo[SearchConfigExperiment] with SearchConfigExperimentRepo {

  import db.Driver.simple._

  type RepoImpl = SearchConfigExperimentTable
  class SearchConfigExperimentTable(tag: Tag) extends RepoTable[SearchConfigExperiment](db, tag, "search_config_experiment") {
    def weight = column[Double]("weight", O.NotNull)
    def description = column[String]("description", O.NotNull)
    def config = column[SearchConfig]("config", O.NotNull)
    def startedAt = column[Option[DateTime]]("started_at", O.NotNull)
    def * = (id.?, weight, description, config, startedAt, state, createdAt, updatedAt) <>
      ((SearchConfigExperiment.apply _).tupled, SearchConfigExperiment.unapply _)
  }

  def table(tag: Tag) = new SearchConfigExperimentTable(tag)
  initTable()

  override def deleteCache(model: SearchConfigExperiment)(implicit session: RSession): Unit = {
    cache.remove(ActiveExperimentsKey)
  }

  override def invalidateCache(model: SearchConfigExperiment)(implicit session: RSession): Unit = {
    cache.remove(ActiveExperimentsKey)
  }

  def getActive()(implicit session: RSession): Seq[SearchConfigExperiment] = {
    cache.getOrElseUpdate {
      (for (v <- rows if v.state === SearchConfigExperimentStates.ACTIVE) yield v).list
    }
  }

  override def save(model: SearchConfigExperiment)(implicit session: RWSession) = {
    cache.remove()
    super.save(model)
  }

  def getNotInactive()(implicit session: RSession): Seq[SearchConfigExperiment] =
    (for (v <- rows if v.state =!= SearchConfigExperimentStates.INACTIVE) yield v).list
}
