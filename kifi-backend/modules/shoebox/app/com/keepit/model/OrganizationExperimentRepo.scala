package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[OrganizationExperimentRepoImpl])
trait OrganizationExperimentRepo extends Repo[OrganizationExperiment] with RepoWithDelete[OrganizationExperiment] {
  def hasExperiment(orgId: Id[Organization], experimentType: OrganizationExperimentType)(implicit session: RSession): Boolean
  def getOrganizationExperiments(orgId: Id[Organization])(implicit session: RSession): Set[OrganizationExperimentType]
  def getOrganizationsByExperiment(experimentType: OrganizationExperimentType)(implicit session: RSession): Seq[Id[Organization]]
  def getByOrganizationIdAndExperimentType(orgId: Id[Organization], experimentType: OrganizationExperimentType, excludeStates: Set[State[OrganizationExperiment]] = Set(OrganizationExperimentStates.INACTIVE))(implicit session: RSession): Option[OrganizationExperiment]
  def deactivate(model: OrganizationExperiment)(implicit session: RWSession): Unit
}

@Singleton
class OrganizationExperimentRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val orgRepo: OrganizationRepo,
    orgExperimentCache: OrganizationExperimentCache) extends DbRepo[OrganizationExperiment] with DbRepoWithDelete[OrganizationExperiment] with OrganizationExperimentRepo {

  import db.Driver.simple._

  type RepoImpl = OrganizationExperimentTable
  class OrganizationExperimentTable(tag: Tag) extends RepoTable[OrganizationExperiment](db, tag, "organization_experiment") {
    def orgId = column[Id[Organization]]("organization_id", O.NotNull)
    def experimentType = column[OrganizationExperimentType]("experiment_type", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, orgId, experimentType) <> ((OrganizationExperiment.apply _).tupled, OrganizationExperiment.unapply)
  }

  def table(tag: Tag) = new OrganizationExperimentTable(tag)
  initTable()

  override def save(model: OrganizationExperiment)(implicit session: RWSession): OrganizationExperiment = {
    val saved = super.save(model)
    orgRepo.save(orgRepo.get(model.orgId)) // just to bump up org seqNum
    saved
  }

  def hasExperiment(orgId: Id[Organization], experimentType: OrganizationExperimentType)(implicit session: RSession): Boolean = {
    getOrganizationExperiments(orgId).contains(experimentType)
  }

  def getOrganizationExperiments(orgId: Id[Organization])(implicit session: RSession): Set[OrganizationExperimentType] = {
    orgExperimentCache.getOrElse(OrganizationExperimentOrganizationIdKey(orgId)) {
      (for (f <- rows if f.orgId === orgId && f.state === OrganizationExperimentStates.ACTIVE) yield f.experimentType).list
    }.toSet
  }

  def getOrganizationsByExperiment(experimentType: OrganizationExperimentType)(implicit session: RSession): Seq[Id[Organization]] = {
    (for (f <- rows if f.experimentType === experimentType && f.state === OrganizationExperimentStates.ACTIVE) yield f.orgId).list
  }
  def getByOrganizationIdAndExperimentType(orgId: Id[Organization], experimentType: OrganizationExperimentType, excludeStates: Set[State[OrganizationExperiment]] = Set(OrganizationExperimentStates.INACTIVE))(implicit session: RSession): Option[OrganizationExperiment] = {
    (for (f <- rows if f.orgId === orgId && f.experimentType === experimentType && !f.state.inSet(excludeStates)) yield f).firstOption
  }
  def deactivate(model: OrganizationExperiment)(implicit session: RWSession): Unit = {
    save(model.withState(OrganizationExperimentStates.INACTIVE))
  }

  override def invalidateCache(model: OrganizationExperiment)(implicit session: RSession): Unit = {
    orgExperimentCache.remove(OrganizationExperimentOrganizationIdKey(model.orgId))
  }

  override def deleteCache(model: OrganizationExperiment)(implicit session: RSession): Unit = {
    orgExperimentCache.remove(OrganizationExperimentOrganizationIdKey(model.orgId))
  }
}
