package com.keepit.cortex.dbmodel

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.cortex.sql.CortexTypeMappers

@ImplementedBy(classOf[PersonaLDAFeatureRepoImpl])
trait PersonaLDAFeatureRepo extends DbRepo[PersonaLDAFeature] {
  def getPersonaFeature(pid: Id[Persona], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[PersonaLDAFeature]
}

@Singleton
class PersonaLDAFeatureRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[PersonaLDAFeature] with PersonaLDAFeatureRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = PersonaFeatureRepoTable

  class PersonaFeatureRepoTable(tag: Tag) extends RepoTable[PersonaLDAFeature](db, tag, "persona_lda_feature") {
    def personaId = column[Id[Persona]]("persona_id")
    def version = column[ModelVersion[DenseLDA]]("version")
    def feature = column[UserTopicMean]("feature")
    def firstTopic = column[LDATopic]("first_topic")
    def secondTopic = column[LDATopic]("second_topic")
    def thirdTopic = column[LDATopic]("third_topic")
    def * = (id.?, createdAt, updatedAt, personaId, version, feature, firstTopic, secondTopic, thirdTopic, state) <> ((PersonaLDAFeature.apply _).tupled, PersonaLDAFeature.unapply _)
  }

  def table(tag: Tag) = new PersonaFeatureRepoTable(tag)
  initTable()

  def deleteCache(model: PersonaLDAFeature)(implicit session: RSession): Unit = {}
  def invalidateCache(model: PersonaLDAFeature)(implicit session: RSession): Unit = {}

  def getPersonaFeature(pid: Id[Persona], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[PersonaLDAFeature] = {
    (for (r <- rows if r.personaId === pid && r.version === version) yield r).firstOption
  }

}
