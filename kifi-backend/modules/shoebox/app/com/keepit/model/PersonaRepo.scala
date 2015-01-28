package com.keepit.model

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock

@ImplementedBy(classOf[PersonaRepoImpl])
trait PersonaRepo extends DbRepo[Persona] {
  def getByName(name: String)(implicit session: RSession): Option[Persona]
  def getByNames(names: Set[String])(implicit session: RSession): Seq[Persona]
  def getPersonasByIds(personaIds: Set[Id[Persona]])(implicit session: RSession): Seq[Persona]
}

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

  private val getByNameCompiled = Compiled { (name: Column[String]) =>
    (for (r <- rows if r.name === name && r.state === PersonaStates.ACTIVE) yield r)
  }
  def getByName(name: String)(implicit session: RSession): Option[Persona] = {
    getByNameCompiled(name).firstOption
  }

  def getByNames(names: Set[String])(implicit session: RSession): Seq[Persona] = {
    (for { r <- rows if r.name.inSet(names) && r.state === PersonaStates.ACTIVE } yield r).list.toSeq
  }

  def getPersonasByIds(personaIds: Set[Id[Persona]])(implicit session: RSession): Seq[Persona] = {
    (for { r <- rows if r.id.inSet(personaIds) && r.state === PersonaStates.ACTIVE } yield r).list.toSeq
  }

}
