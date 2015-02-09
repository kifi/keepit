package com.keepit.model

import javax.inject.Provider

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[UserPersonaRepoImpl])
trait UserPersonaRepo extends DbRepo[UserPersona] {
  def getByUserAndPersonaId(userId: Id[User], personaId: Id[Persona])(implicit session: RSession): Option[UserPersona]
  def getFirstPersonaForUser(userId: Id[User])(implicit session: RSession): Option[Persona]
  def getPersonasForUser(userId: Id[User])(implicit session: RSession): Seq[Persona]
  def getPersonaIdsForUser(userId: Id[User])(implicit session: RSession): Seq[Id[Persona]]
  def getUserActivePersonas(userId: Id[User])(implicit session: RSession): UserActivePersonas
}

@Singleton
class UserPersonaRepoImpl @Inject() (
    val db: DataBaseComponent,
    userActivePersonasCache: UserActivePersonasCache,
    val personaRepo: Provider[PersonaRepoImpl],
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[UserPersona] with UserPersonaRepo {

  import db.Driver.simple._

  type RepoImpl = UserPersonaRepoTable

  implicit val personaNameMapper = MappedColumnType.base[PersonaName, String](_.value, PersonaName.apply)

  class UserPersonaRepoTable(tag: Tag) extends RepoTable[UserPersona](db, tag, "user_persona") {
    def userId = column[Id[User]]("user_id")
    def personaId = column[Id[Persona]]("persona_id")
    def * = (id.?, createdAt, updatedAt, userId, personaId, state) <> ((UserPersona.apply _).tupled, UserPersona.unapply _)
  }

  def table(tag: Tag) = new UserPersonaRepoTable(tag)
  initTable()

  implicit def userId2UserPersonasKey(userId: Id[User]): UserActivePersonasKey = UserActivePersonasKey(userId)

  def deleteCache(model: UserPersona)(implicit session: RSession): Unit = {
    userActivePersonasCache.remove(model.userId)
  }

  def invalidateCache(model: UserPersona)(implicit session: RSession): Unit = {
    val current = {
      val (pids, updates) = getUserPersonaIdAndUpdatedAtCompiled(model.userId).list.unzip
      UserActivePersonas(pids, updates)
    }
    userActivePersonasCache.set(model.userId, current)
  }

  override def save(model: UserPersona)(implicit session: RWSession): UserPersona = {
    val saved = super.save(model)
    invalidateCache(saved)
    saved
  }

  private val getByUserAndPersonaCompiled = Compiled { (userId: Column[Id[User]], personaId: Column[Id[Persona]]) =>
    (for (r <- rows if r.userId === userId && r.personaId === personaId) yield r)
  }
  def getByUserAndPersonaId(userId: Id[User], personaId: Id[Persona])(implicit session: RSession): Option[UserPersona] = {
    getByUserAndPersonaCompiled(userId, personaId).firstOption
  }

  private val getPersonaIdsForUserCompiled = Compiled { (userId: Column[Id[User]]) =>
    (for (r <- rows if r.userId === userId && r.state === UserPersonaStates.ACTIVE) yield r.personaId)
  }
  def getPersonaIdsForUser(userId: Id[User])(implicit session: RSession): Seq[Id[Persona]] = {
    getPersonaIdsForUserCompiled(userId).list
  }

  private def personasForUser(userId: Id[User]) = {
    for {
      up <- rows if up.userId === userId && up.state === UserPersonaStates.ACTIVE
      p <- personaRepo.get.rows if p.id === up.personaId && p.state === PersonaStates.ACTIVE
    } yield (p)
  }

  def getFirstPersonaForUser(userId: Id[User])(implicit session: RSession): Option[Persona] = {
    personasForUser(userId).firstOption
  }

  def getPersonasForUser(userId: Id[User])(implicit session: RSession): Seq[Persona] = {
    personasForUser(userId).list
  }

  private val getUserPersonaIdAndUpdatedAtCompiled = Compiled { (userId: Column[Id[User]]) =>
    (for (r <- rows if r.userId === userId && r.state === UserPersonaStates.ACTIVE) yield (r.personaId, r.updatedAt))
  }

  def getUserActivePersonas(userId: Id[User])(implicit session: RSession): UserActivePersonas = {
    userActivePersonasCache.getOrElse(userId) {
      val (pids, updates) = getUserPersonaIdAndUpdatedAtCompiled(userId).list.unzip
      UserActivePersonas(pids, updates)
    }
  }

}
