package com.keepit.model

import javax.inject.Provider

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[UserPersonaRepoImpl])
trait UserPersonaRepo extends DbRepo[UserPersona] {
  def getByUserAndPersona(userId: Id[User], personaId: Id[Persona])(implicit session: RSession): Option[UserPersona]
  def getPersonasForUser(userId: Id[User])(implicit session: RSession): Seq[Persona]
  def getPersonaIdsForUser(userId: Id[User])(implicit session: RSession): Seq[Id[Persona]]
  def getUserActivePersonas(userId: Id[User])(implicit session: RSession): UserActivePersonas
  def getUserLastEditTime(userId: Id[User])(implicit session: RSession): Option[DateTime]
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
    val current = getUserActivePersonas(model.userId)
    userActivePersonasCache.set(model.userId, current)
  }

  private val getByUserAndPersonaCompiled = Compiled { (userId: Column[Id[User]], personaId: Column[Id[Persona]]) =>
    (for (r <- rows if r.userId === userId && r.personaId === personaId) yield r)
  }
  def getByUserAndPersona(userId: Id[User], personaId: Id[Persona])(implicit session: RSession): Option[UserPersona] = {
    getByUserAndPersonaCompiled(userId, personaId).firstOption
  }

  private val getPersonaIdsForUserCompiled = Compiled { (userId: Column[Id[User]]) =>
    (for (r <- rows if r.userId === userId && r.state === UserPersonaStates.ACTIVE) yield r.personaId)
  }
  def getPersonaIdsForUser(userId: Id[User])(implicit session: RSession): Seq[Id[Persona]] = {
    getPersonaIdsForUserCompiled(userId).list
  }

  def getPersonasForUser(userId: Id[User])(implicit session: RSession): Seq[Persona] = {
    val q = for {
      up <- rows if up.userId === userId && up.state === UserPersonaStates.ACTIVE
      p <- personaRepo.get.rows if p.id === up.personaId && p.state === PersonaStates.ACTIVE
    } yield (p)
    q.list
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

  private val getUserLastEditTimeCompiled = Compiled { (userId: Column[Id[User]]) =>
    (for (r <- rows if r.userId === userId) yield r).sortBy(_.updatedAt.desc)
  }
  def getUserLastEditTime(userId: Id[User])(implicit session: RSession): Option[DateTime] = {
    getUserLastEditTimeCompiled(userId).firstOption.map { _.updatedAt }
  }
}
