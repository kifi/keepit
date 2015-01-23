package com.keepit.model

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock

@ImplementedBy(classOf[PersonaRepoImpl])
trait PersonaRepo extends DbRepo[Persona]

@Singleton
class PersonaRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[Persona] with PersonaRepo {

  import db.Driver.simple._

  type RepoImpl = PersonaRepoTable

  class PersonaRepoTable(tag: Tag) extends RepoTable[Persona](db, tag, "persona") {
    def name = column[String]("name")
    def * = (id.?, createdAt, updatedAt, name, state) <> ((Persona.apply _).tupled, Persona.unapply _)
  }

  def table(tag: Tag) = new PersonaRepoTable(tag)
  initTable()

  def deleteCache(model: Persona)(implicit session: RSession): Unit = {}
  def invalidateCache(model: Persona)(implicit session: RSession): Unit = {}

}
