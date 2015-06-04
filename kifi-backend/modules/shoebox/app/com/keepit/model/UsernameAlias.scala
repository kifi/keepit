package com.keepit.model

import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.commanders.HandleOps
import com.amazonaws.services.redshift.model.UnauthorizedOperationException
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

case class LockedUsernameException(alias: UsernameAlias)
  extends UnauthorizedOperationException(s"Username ${alias.username} is locked as an alias by user ${alias.userId}")

case class ProtectedUsernameException(alias: UsernameAlias)
  extends UnauthorizedOperationException(s"Username ${alias.username} is protected as an alias by user ${alias.userId}")

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
  def isLocked = (state == UsernameAliasStates.LOCKED)
  def isProtected = (state == UsernameAliasStates.ACTIVE && (currentDateTime isBefore (lastActivatedAt plusSeconds UsernameAlias.gracePeriod.toSeconds.toInt)))
  def belongsTo(thatUserId: Id[User]) = (userId == thatUserId)
}

object UsernameAlias {
  private[UsernameAlias] val gracePeriod = 7 days // an active alias should be protected for this period of time after it was last activated
}

object UsernameAliasStates extends States[UsernameAlias] {
  val LOCKED = State[UsernameAlias]("locked") // while an 'active' alias can be claimed by another user after the grace period (best effort), a 'locked' alias must be explicitly unlocked first)
}

@ImplementedBy(classOf[UsernameAliasRepoImpl])
trait UsernameAliasRepo extends Repo[UsernameAlias] {
  def getByUsername(username: Username, excludeState: Option[State[UsernameAlias]] = Some(UsernameAliasStates.INACTIVE))(implicit session: RSession): Option[UsernameAlias]
  def getByUserId(userId: Id[User])(implicit session: RSession): List[UsernameAlias]
  def alias(username: Username, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[UsernameAlias]
  def unlock(username: Username)(implicit session: RWSession): Boolean
  def reclaim(username: Username, requestingUserId: Option[Id[User]] = None, overrideProtection: Boolean = false)(implicit session: RWSession): Try[Option[Id[User]]]
}

@Singleton
class UsernameAliasRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[UsernameAlias] with UsernameAliasRepo with Logging {

  import UsernameAlias._
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

  private val compiledGetByUserId = Compiled { (userId: Column[Id[User]]) =>
    for (row <- rows if row.userId === userId) yield row
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): List[UsernameAlias] = {
    compiledGetByUserId(userId).list
  }

  private def getByNormalizedUsername(normalizedUsername: Username, excludeState: Option[State[UsernameAlias]])(implicit session: RSession) = {
    val q = excludeState match {
      case Some(state) => compiledGetByNormalizedUsernameAndExcludedState(normalizedUsername, state)
      case None => compiledGetByNormalizedUsername(normalizedUsername)
    }
    q.firstOption
  }

  private def normalize(username: Username): Username = {
    Username(HandleOps.normalize(username.value))
  }

  def getByUsername(username: Username, excludeState: Option[State[UsernameAlias]] = Some(INACTIVE))(implicit session: RSession): Option[UsernameAlias] = {
    getByNormalizedUsername(normalize(username), excludeState)
  }

  def alias(username: Username, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[UsernameAlias] = {
    val requestedAlias = {
      val normalizedUsername = normalize(username)
      val requestedState = if (lock) LOCKED else ACTIVE
      getByNormalizedUsername(normalizedUsername, excludeState = None) match { // locked aliases must be explicitly released
        case Some(alias) if alias.isLocked => if (alias.belongsTo(userId)) Success(alias) else Failure(LockedUsernameException(alias))
        case Some(alias) if alias.isProtected && !overrideProtection => if (alias.belongsTo(userId)) Success(alias.copy(state = requestedState)) else Failure(ProtectedUsernameException(alias))
        case Some(availableAlias) => Success(availableAlias.copy(state = requestedState, userId = userId))
        case None => Success(UsernameAlias(state = requestedState, username = normalizedUsername, userId = userId))
      }
    }
    requestedAlias.map(alias => save(alias.copy(lastActivatedAt = clock.now)))
  }

  def unlock(username: Username)(implicit session: RWSession): Boolean = {
    getByUsername(username).exists { alias =>
      val wasLocked = alias.isLocked
      if (wasLocked) { save(alias.copy(state = ACTIVE)) }
      wasLocked
    }
  }

  def reclaim(username: Username, requestingUserId: Option[Id[User]] = None, overrideProtection: Boolean = false)(implicit session: RWSession): Try[Option[Id[User]]] = {
    getByUsername(username) match {
      case Some(alias) if alias.isLocked => if (requestingUserId.exists(alias.belongsTo)) Success(deactivate(alias)) else Failure(LockedUsernameException(alias))
      case Some(alias) if alias.isProtected && !overrideProtection => if (requestingUserId.exists(alias.belongsTo)) Success(deactivate(alias)) else Failure(ProtectedUsernameException(alias))
      case Some(availableAlias) => Success(deactivate(availableAlias))
      case None => Success(None)
    }
  }

  private def deactivate(alias: UsernameAlias)(implicit session: RWSession): Option[Id[User]] = {
    if (alias.isLocked) throw LockedUsernameException(alias)
    if (alias.state == ACTIVE) {
      log.info(s"Reclaiming alias from username ${alias.username} to user ${alias.userId}.")
      save(alias.copy(state = INACTIVE))
      Some(alias.userId)
    } else None
  }
}

