package com.keepit.model

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock

@ImplementedBy(classOf[PersonaRepoImpl])
trait PersonaRepo extends DbRepo[Persona] {
  def getByName(name: PersonaName)(implicit session: RSession): Option[Persona]
  def getByNames(names: Set[PersonaName])(implicit session: RSession): Map[PersonaName, Persona]
  def getPersonasByIds(personaIds: Set[Id[Persona]])(implicit session: RSession): Seq[Persona]
  def getByState(state: State[Persona])(implicit session: RSession): Seq[Persona]
}

@Singleton
class PersonaRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[Persona] with PersonaRepo {

  import db.Driver.simple._

  type RepoImpl = PersonaRepoTable

  implicit val personaNameMapper = MappedColumnType.base[PersonaName, String](_.value, PersonaName.apply)

  class PersonaRepoTable(tag: Tag) extends RepoTable[Persona](db, tag, "persona") {
    def name = column[PersonaName]("name", O.NotNull)
    def displayName = column[String]("display_name", O.NotNull)
    def displayNamePlural = column[String]("display_name_plural", O.NotNull)
    def iconPath = column[String]("icon_path", O.NotNull)
    def activeIconPath = column[String]("active_icon_path", O.NotNull)
    def * = (id.?, createdAt, updatedAt, name, state, displayName, displayNamePlural, iconPath, activeIconPath) <> ((Persona.apply _).tupled, Persona.unapply _)
  }

  def table(tag: Tag) = new PersonaRepoTable(tag)
  initTable()

  def deleteCache(model: Persona)(implicit session: RSession): Unit = {}
  def invalidateCache(model: Persona)(implicit session: RSession): Unit = {}

  private val getByNameCompiled = Compiled { (name: Column[PersonaName]) =>
    (for (r <- rows if r.name === name && r.state === PersonaStates.ACTIVE) yield r)
  }
  def getByName(name: PersonaName)(implicit session: RSession): Option[Persona] = {
    getByNameCompiled(name).firstOption
  }

  def getByNames(names: Set[PersonaName])(implicit session: RSession): Map[PersonaName, Persona] = {
    (for { r <- rows if r.name.inSet(names) && r.state === PersonaStates.ACTIVE } yield (r.name, r)).list.toMap
  }

  def getPersonasByIds(personaIds: Set[Id[Persona]])(implicit session: RSession): Seq[Persona] = {
    (for { r <- rows if r.id.inSet(personaIds) && r.state === PersonaStates.ACTIVE } yield r).list.toSeq
  }

  private val getByStateCompiled = Compiled { (state: Column[State[Persona]]) =>
    (for (r <- rows if r.state === state) yield r).sortBy(_.createdAt)
  }
  def getByState(state: State[Persona])(implicit session: RSession): Seq[Persona] = {
    getByStateCompiled(state).list
  }

}
