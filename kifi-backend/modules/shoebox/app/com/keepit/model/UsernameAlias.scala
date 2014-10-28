package com.keepit.model

import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.commanders.UsernameOps
import com.amazonaws.services.redshift.model.UnauthorizedOperationException

case class ReservedUsernameException(alias: UsernameAlias)
  extends UnauthorizedOperationException(s"Username ${alias.username} is reserved for user ${alias.userId}")

case class UsernameAlias(
    id: Option[Id[UsernameAlias]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[UsernameAlias] = UsernameAliasStates.ACTIVE,
    username: Username, // normalized
    userId: Id[User]) extends ModelWithState[UsernameAlias] {
  def withId(id: Id[UsernameAlias]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isReserved = (state == UsernameAliasStates.RESERVED)
}

object UsernameAliasStates extends States[UsernameAlias] {
  val RESERVED = State[UsernameAlias]("reserved")
}

@ImplementedBy(classOf[UsernameAliasRepoImpl])
trait UsernameAliasRepo extends Repo[UsernameAlias] {
  def getByUsername(username: Username, excludeState: Option[State[UsernameAlias]] = Some(UsernameAliasStates.INACTIVE))(implicit session: RSession): Option[UsernameAlias]
  def intern(username: Username, userId: Id[User])(implicit session: RWSession): UsernameAlias
  def reserve(username: Username, userId: Id[User])(implicit session: RWSession): Unit
  def release(username: Username)(implicit session: RWSession): Boolean
}

@Singleton
class UsernameAliasRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[UsernameAlias] with UsernameAliasRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = UsernameAliasTable
  class UsernameAliasTable(tag: Tag) extends RepoTable[UsernameAlias](db, tag, "username_alias") {
    def username = column[Username]("username", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, username, userId) <> ((UsernameAlias.apply _).tupled, UsernameAlias.unapply _)
  }

  def table(tag: Tag) = new UsernameAliasTable(tag)
  initTable

  override def invalidateCache(usernameAlias: UsernameAlias)(implicit session: RSession): Unit = {}

  override def deleteCache(usernameAlias: UsernameAlias)(implicit session: RSession): Unit = {}

  private def compiledGetByNormalizedUsername(normalizedUsername: Username, excludeState: Option[State[UsernameAlias]]) = Compiled {
    for (row <- rows if row.username === normalizedUsername && row.state =!= excludeState.orNull) yield row
  }

  private def getByNormalizedUsername(normalizedUsername: Username, excludeState: Option[State[UsernameAlias]])(implicit session: RSession) = {
    compiledGetByNormalizedUsername(normalizedUsername, excludeState).firstOption
  }

  private def normalize(username: Username): Username = {
    Username(UsernameOps.normalize(username.value))
  }

  def getByUsername(username: Username, excludeState: Option[State[UsernameAlias]] = Some(UsernameAliasStates.INACTIVE))(implicit session: RSession): Option[UsernameAlias] = {
    getByNormalizedUsername(normalize(username), excludeState)
  }

  def intern(username: Username, userId: Id[User])(implicit session: RWSession): UsernameAlias = {
    val normalizedUsername = normalize(username)
    getByNormalizedUsername(normalizedUsername, excludeState = None) match {
      case None => save(UsernameAlias(username = normalizedUsername, userId = userId))
      case Some(inactiveAlias) if inactiveAlias.state == UsernameAliasStates.INACTIVE => {
        save(inactiveAlias.copy(createdAt = clock.now, updatedAt = clock.now, state = UsernameAliasStates.ACTIVE, username = normalizedUsername, userId = userId))
      }
      case Some(validAlias) if validAlias.userId == userId => validAlias
      case Some(reservedAlias) if reservedAlias.isReserved => throw ReservedUsernameException(reservedAlias)
      case Some(unreservedAlias) => save(unreservedAlias.copy(userId = userId))
    }
  }

  def reserve(username: Username, userId: Id[User])(implicit session: RWSession): Unit = {
    val alias = intern(username, userId)
    if (!alias.isReserved) { save(alias.copy(state = UsernameAliasStates.RESERVED)) }
  }

  def release(username: Username)(implicit session: RWSession): Boolean = {
    getByUsername(username).exists { alias =>
      val wasReserved = alias.isReserved
      if (wasReserved) { save(alias.copy(state = UsernameAliasStates.ACTIVE)) }
      wasReserved
    }
  }

}

