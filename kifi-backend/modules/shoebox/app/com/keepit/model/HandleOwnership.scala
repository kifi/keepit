package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.commanders.HandleOps
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.concurrent.duration._

import scala.util.{ Failure, Success, Try }

case class Handle(value: String) {
  override def toString() = value
}

object Handle {
  implicit def fromUsername(username: Username) = Handle(username.value)
  implicit def fromOrganizationHandle(organizationHandle: OrganizationHandle) = Handle(organizationHandle.value)
}

case class LockedHandleException(ownership: HandleOwnership)
  extends Exception(s"Handle ${ownership.handle} is locked, owned by ${ownership.prettyOwner}.")

case class ProtectedHandleException(ownership: HandleOwnership)
  extends Exception(s"Handle ${ownership.handle} is protected, owned by ${ownership.prettyOwner}.")

case class HandleOwnership(
    id: Option[Id[HandleOwnership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    lastClaimedAt: DateTime = currentDateTime,
    state: State[HandleOwnership] = HandleOwnershipStates.ACTIVE,
    locked: Boolean = false, // while a handle can be claimed after the grace period (best effort), a 'locked' handle must be explicitly unlocked first
    handle: Handle, // normalized
    ownerId: Option[Either[Id[Organization], Id[User]]]) extends ModelWithState[HandleOwnership] {
  def withId(id: Id[HandleOwnership]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == HandleOwnershipStates.ACTIVE)
  def isLocked = locked
  def isProtected = (isActive && !isLocked && (currentDateTime isBefore (lastClaimedAt plusSeconds HandleOwnership.gracePeriod.toSeconds.toInt)))
  def belongsToOrg(orgId: Id[Organization]) = belongsTo(Some(Left(orgId)))
  def belongsToUser(userId: Id[User]) = belongsTo(Some(Right(userId)))
  def belongsToSystem = belongsTo(None)
  def belongsTo(thatOwnerId: Option[Either[Id[Organization], Id[User]]]) = (ownerId == thatOwnerId)
  def prettyOwner: String = ownerId match {
    case Some(Left(orgId)) => s"organization $orgId"
    case Some(Right(userId)) => s"user $userId"
    case None => "system"
  }
}

object HandleOwnership {
  private[HandleOwnership] val gracePeriod = 7 days // an active handle should be protected for this period of time after it was last claimed

  def applyFromDbRow(id: Option[Id[HandleOwnership]],
    createdAt: DateTime,
    updatedAt: DateTime,
    lastClaimedAt: DateTime,
    state: State[HandleOwnership],
    locked: Boolean = false,
    handle: Handle,
    organizationId: Option[Id[Organization]],
    userId: Option[Id[User]]) = {
    val ownerId = organizationId.map(Left(_)) orElse userId.map(Right(_))
    HandleOwnership(id, createdAt, updatedAt, lastClaimedAt, state, locked, handle, ownerId)
  }

  def unapplyToDbRow(ownership: HandleOwnership) = {
    Some((ownership.id,
      ownership.createdAt,
      ownership.updatedAt,
      ownership.lastClaimedAt,
      ownership.state,
      ownership.locked,
      ownership.handle,
      ownership.ownerId.flatMap(_.left.toOption),
      ownership.ownerId.flatMap(_.right.toOption)))
  }
}

object HandleOwnershipStates extends States[HandleOwnership]

@ImplementedBy(classOf[HandleOwnershipRepoImpl])
trait HandleOwnershipRepo extends Repo[HandleOwnership] {
  def getByHandle(handle: Handle, excludeState: Option[State[HandleOwnership]] = Some(HandleOwnershipStates.INACTIVE))(implicit session: RSession): Option[HandleOwnership]
  def getByOwnerId(ownerId: Either[Id[Organization], Id[User]])(implicit session: RSession): Seq[HandleOwnership]
  def setOrganizationOwnership(handle: Handle, organizationId: Id[Organization], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership]
  def setUserOwnership(handle: Handle, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership]
  def setSystemOwnership(handle: Handle, lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership]
  def unlock(handle: Handle)(implicit session: RWSession): Boolean
}

@Singleton
class HandleOwnershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[HandleOwnership] with HandleOwnershipRepo with Logging {

  import HandleOwnershipStates._
  import db.Driver.simple._

  implicit val handleTypeMapper = MappedColumnType.base[Handle, String](_.value, Handle.apply)

  type RepoImpl = HandleOwnershipTable
  class HandleOwnershipTable(tag: Tag) extends RepoTable[HandleOwnership](db, tag, "handle_ownership") {
    def lastClaimedAt = column[DateTime]("last_claimed_at", O.NotNull)
    def locked = column[Boolean]("locked", O.NotNull)
    def handle = column[Handle]("handle", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, lastClaimedAt, state, locked, handle, organizationId, userId) <> ((HandleOwnership.applyFromDbRow _).tupled, HandleOwnership.unapplyToDbRow _)
  }

  def table(tag: Tag) = new HandleOwnershipTable(tag)
  initTable

  override def invalidateCache(usernameAlias: HandleOwnership)(implicit session: RSession): Unit = {}

  override def deleteCache(usernameAlias: HandleOwnership)(implicit session: RSession): Unit = {}

  private val compiledGetByNormalizedHandle = Compiled { (normalizedHandle: Column[Handle]) =>
    for (row <- rows if row.handle === normalizedHandle) yield row
  }

  private val compiledGetByNormalizedHandleAndExcludedState = Compiled { (normalizedHandle: Column[Handle], excludedState: Column[State[HandleOwnership]]) =>
    for (row <- rows if row.handle === normalizedHandle && row.state =!= excludedState) yield row
  }

  private def getByNormalizedHandle(normalizedHandle: Handle, excludeState: Option[State[HandleOwnership]])(implicit session: RSession) = {
    val q = excludeState match {
      case Some(state) => compiledGetByNormalizedHandleAndExcludedState(normalizedHandle, state)
      case None => compiledGetByNormalizedHandle(normalizedHandle)
    }
    q.firstOption
  }

  private def normalize(handle: Handle): Handle = {
    Handle(HandleOps.normalize(handle.value))
  }

  def getByHandle(handle: Handle, excludeState: Option[State[HandleOwnership]] = Some(INACTIVE))(implicit session: RSession): Option[HandleOwnership] = {
    getByNormalizedHandle(normalize(handle), excludeState)
  }

  private val compiledGetByUserId = Compiled { (userId: Column[Id[User]]) =>
    for (row <- rows if row.userId === userId) yield row
  }

  private val compiledGetByOrganizationId = Compiled { (organizationId: Column[Id[Organization]]) =>
    for (row <- rows if row.organizationId === organizationId) yield row
  }

  def getByOwnerId(ownerId: Either[Id[Organization], Id[User]])(implicit session: RSession): Seq[HandleOwnership] = {
    val q = ownerId match {
      case Left(orgId) => compiledGetByOrganizationId(orgId)
      case Right(userId) => compiledGetByUserId(userId)
    }
    q.list
  }

  private def setOwner(handle: Handle, ownerId: Option[Either[Id[Organization], Id[User]]], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    val ownershipMaybe = {
      val normalizedHandle = normalize(handle)
      getByNormalizedHandle(normalizedHandle, excludeState = None) match { // locked handles must be explicitly released
        case Some(ownership) if ownership.isLocked => if (ownership.belongsTo(ownerId)) Success(ownership) else Failure(LockedHandleException(ownership))
        case Some(ownership) if ownership.isProtected && !overrideProtection => if (ownership.belongsTo(ownerId)) Success(ownership) else Failure(ProtectedHandleException(ownership))
        case Some(availableOwnership) => Success(availableOwnership.copy(state = ACTIVE, ownerId = ownerId))
        case None => Success(HandleOwnership(handle = normalizedHandle, ownerId = ownerId))
      }
    }
    ownershipMaybe.map(ownership => save(ownership.copy(lastClaimedAt = clock.now, locked = ownership.locked || lock)))
  }

  def setOrganizationOwnership(handle: Handle, organizationId: Id[Organization], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    setOwner(handle, Some(Left(organizationId)), lock, overrideProtection)
  }
  def setUserOwnership(handle: Handle, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    setOwner(handle, Some(Right(userId)), lock, overrideProtection)
  }
  def setSystemOwnership(handle: Handle, lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    setOwner(handle, None, lock, overrideProtection)
  }

  def unlock(handle: Handle)(implicit session: RWSession): Boolean = {
    getByHandle(handle).exists { ownership =>
      val wasLocked = ownership.isLocked
      if (wasLocked) { save(ownership.copy(locked = false)) }
      wasLocked
    }
  }
}

