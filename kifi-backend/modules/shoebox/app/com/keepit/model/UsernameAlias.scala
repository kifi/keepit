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
import scala.util.{ Failure, Success, Try }

case class ReservedUsernameException(alias: UsernameAlias)
  extends UnauthorizedOperationException(s"Username ${alias.username} is reserved for user ${alias.userId}")

case class UsernameAlias(
    id: Option[Id[UsernameAlias]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    lastActivatedAt: DateTime = currentDateTime,
    state: State[UsernameAlias] = UsernameAliasStates.ACTIVE,
    username: Username, // normalized
    userId: Id[User]) extends ModelWithState[UsernameAlias] {
  def withId(id: Id[UsernameAlias]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isReserved = (state == UsernameAliasStates.RESERVED)
}

object UsernameAliasStates extends States[UsernameAlias] {
  val RESERVED = State[UsernameAlias]("reserved") // while an 'active' alias can be claimed by another user (soft alias), a 'reserved' alias cannot (hard alias)
}

@ImplementedBy(classOf[UsernameAliasRepoImpl])
trait UsernameAliasRepo extends Repo[UsernameAlias] {
  def getByUsername(username: Username, excludeState: Option[State[UsernameAlias]] = Some(UsernameAliasStates.INACTIVE))(implicit session: RSession): Option[UsernameAlias]
  def alias(username: Username, userId: Id[User], reserve: Boolean = false)(implicit session: RWSession): Try[UsernameAlias]
  def release(username: Username)(implicit session: RWSession): Boolean
}

@Singleton
class UsernameAliasRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[UsernameAlias] with UsernameAliasRepo with Logging {

  import UsernameAliasStates._
  import db.Driver.simple._

  type RepoImpl = UsernameAliasTable
  class UsernameAliasTable(tag: Tag) extends RepoTable[UsernameAlias](db, tag, "username_alias") {
    def lastActivatedAt = column[DateTime]("last_activated_at", O.NotNull)
    def username = column[Username]("username", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, lastActivatedAt, state, username, userId) <> ((UsernameAlias.apply _).tupled, UsernameAlias.unapply _)
  }

  def table(tag: Tag) = new UsernameAliasTable(tag)
  initTable

  override def invalidateCache(usernameAlias: UsernameAlias)(implicit session: RSession): Unit = {}

  override def deleteCache(usernameAlias: UsernameAlias)(implicit session: RSession): Unit = {}

  private val compiledGetByNormalizedUsername = Compiled { (normalizedUsername: Column[Username]) =>
    for (row <- rows if row.username === normalizedUsername) yield row
  }

  private val compiledGetByNormalizedUsernameAndExcludedState = Compiled { (normalizedUsername: Column[Username], excludedState: Column[State[UsernameAlias]]) =>
    for (row <- rows if row.username === normalizedUsername && row.state =!= excludedState) yield row
  }

  private def getByNormalizedUsername(normalizedUsername: Username, excludeState: Option[State[UsernameAlias]])(implicit session: RSession) = {
    val q = excludeState match {
      case Some(state) => compiledGetByNormalizedUsernameAndExcludedState(normalizedUsername, state)
      case None => compiledGetByNormalizedUsername(normalizedUsername)
    }
    q.firstOption
  }

  private def normalize(username: Username): Username = {
    Username(UsernameOps.normalize(username.value))
  }

  def getByUsername(username: Username, excludeState: Option[State[UsernameAlias]] = Some(INACTIVE))(implicit session: RSession): Option[UsernameAlias] = {
    getByNormalizedUsername(normalize(username), excludeState)
  }

  def alias(username: Username, userId: Id[User], reserve: Boolean = false)(implicit session: RWSession): Try[UsernameAlias] = {
    val requestedAlias = {
      val normalizedUsername = normalize(username)
      val requestedState = if (reserve) RESERVED else ACTIVE
      getByNormalizedUsername(normalizedUsername, excludeState = None) match { // reserved aliases must be explicitly released
        case Some(alias) if alias.isReserved => if (alias.userId == userId) Success(alias) else Failure(ReservedUsernameException(alias))
        case Some(availableAlias) => Success(availableAlias.copy(state = requestedState, userId = userId))
        case None => Success(UsernameAlias(state = requestedState, username = normalizedUsername, userId = userId))
      }
    }
    requestedAlias.map(alias => save(alias.copy(lastActivatedAt = clock.now)))
  }

  def release(username: Username)(implicit session: RWSession): Boolean = {
    getByUsername(username).exists { alias =>
      val wasReserved = alias.isReserved
      if (wasReserved) { save(alias.copy(state = UsernameAliasStates.ACTIVE)) }
      wasReserved
    }
  }

}

