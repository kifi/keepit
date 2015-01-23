package com.keepit.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.dbmodel.Persona
import org.joda.time.DateTime

@ImplementedBy(classOf[UserPersonaRepoImpl])
trait UserPersonaRepo extends DbRepo[UserPersona] {
  def getByUserAndPersona(userId: Id[User], personaId: Id[Persona])(implicit session: RSession): Option[UserPersona]
  def getUserPersonas(userId: Id[User])(implicit session: RSession): Seq[Id[Persona]]
  def getUserLastEditTime(userId: Id[User])(implicit session: RSession): Option[DateTime]
}

@Singleton
class UserPersonaRepoImpl @Inject() (
    val db: DataBaseComponent,
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

  def deleteCache(model: UserPersona)(implicit session: RSession): Unit = {}
  def invalidateCache(model: UserPersona)(implicit session: RSession): Unit = {}

  def getByUserAndPersona(userId: Id[User], personaId: Id[Persona])(implicit session: RSession): Option[UserPersona] = {
    (for (r <- rows if r.userId === userId && r.personaId === personaId) yield r).firstOption
  }

  def getUserPersonas(userId: Id[User])(implicit session: RSession): Seq[Id[Persona]] = {
    (for (r <- rows if r.userId === userId && r.state === UserPersonaStates.ACTIVE) yield r.personaId).list
  }

  def getUserLastEditTime(userId: Id[User])(implicit session: RSession): Option[DateTime] = {
    (for (r <- rows if r.userId === userId) yield r).sortBy(_.updatedAt.desc).firstOption.map { _.updatedAt }
  }
}
