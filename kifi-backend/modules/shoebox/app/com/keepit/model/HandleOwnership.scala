package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.commanders.HandleOps
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.HandleOwner.{ UserOwner, OrganizationOwner }
import com.kifi.macros.json
import org.joda.time.DateTime
import scala.concurrent.duration._

@json
case class Handle(value: String) extends AnyVal {
  override def toString() = value
}

object Handle {
  implicit def fromUsername(username: Username) = Handle(username.value)
  implicit def fromOrganizationHandle(organizationHandle: OrganizationHandle) = Handle(organizationHandle.value)
  def normalize(handle: Handle): Handle = Handle(HandleOps.normalize(handle.value))
}

sealed trait HandleOwner

object HandleOwner {
  case class UserOwner(id: Id[User]) extends HandleOwner
  case class OrganizationOwner(id: Id[Organization]) extends HandleOwner

  implicit def fromUserId(userId: Id[User]) = UserOwner(userId)
  implicit def fromOrganizationId(organizationId: Id[Organization]) = OrganizationOwner(organizationId)

  def prettyPrint(ownerId: Option[HandleOwner]): String = ownerId match {
    case Some(OrganizationOwner(orgId)) => s"organization $orgId"
    case Some(UserOwner(userId)) => s"user $userId"
    case None => "system"
  }
}

case class HandleOwnership(
    id: Option[Id[HandleOwnership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    lastClaimedAt: DateTime = currentDateTime,
    state: State[HandleOwnership] = HandleOwnershipStates.ACTIVE,
    locked: Boolean = false, // while a handle can be claimed after the grace period (best effort), a 'locked' handle must be explicitly unlocked first
    handle: Handle, // normalized
    ownerId: Option[HandleOwner]) extends ModelWithState[HandleOwnership] {
  def withId(id: Id[HandleOwnership]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == HandleOwnershipStates.ACTIVE)
  def isLocked = isActive && locked
  def isProtected = isActive && !isLocked && (currentDateTime isBefore (lastClaimedAt plusSeconds HandleOwnership.gracePeriod.toSeconds.toInt))
  def belongsToOrg(orgId: Id[Organization]) = belongsTo(Some(orgId))
  def belongsToUser(userId: Id[User]) = belongsTo(Some(userId))
  def belongsToSystem = belongsTo(None)
  def belongsTo(thatOwnerId: Option[HandleOwner]) = isActive && (ownerId == thatOwnerId)
  def prettyOwner = HandleOwner.prettyPrint(ownerId)
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
    val ownerId = organizationId.map(OrganizationOwner(_)) orElse userId.map(UserOwner(_))
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
      ownership.ownerId.collect { case OrganizationOwner(organizationId) => organizationId },
      ownership.ownerId.collect { case UserOwner(userId) => userId }
    ))
  }
}

object HandleOwnershipStates extends States[HandleOwnership]

@ImplementedBy(classOf[HandleOwnershipRepoImpl])
trait HandleOwnershipRepo extends Repo[HandleOwnership] {
  def getByHandle(handle: Handle, excludeState: Option[State[HandleOwnership]] = Some(HandleOwnershipStates.INACTIVE))(implicit session: RSession): Option[HandleOwnership]
  def getByNormalizedHandle(normalizedHandle: Handle, excludeState: Option[State[HandleOwnership]])(implicit session: RSession): Option[HandleOwnership]
  def getByOwnerId(ownerId: Option[HandleOwner], excludeState: Option[State[HandleOwnership]] = Some(HandleOwnershipStates.INACTIVE))(implicit session: RSession): Seq[HandleOwnership]
  def lock(handle: Handle)(implicit session: RWSession): Boolean
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

  def getByNormalizedHandle(normalizedHandle: Handle, excludeState: Option[State[HandleOwnership]])(implicit session: RSession): Option[HandleOwnership] = {
    val q = excludeState match {
      case Some(state) => compiledGetByNormalizedHandleAndExcludedState(normalizedHandle, state)
      case None => compiledGetByNormalizedHandle(normalizedHandle)
    }
    q.firstOption
  }

  def getByHandle(handle: Handle, excludeState: Option[State[HandleOwnership]] = Some(INACTIVE))(implicit session: RSession): Option[HandleOwnership] = {
    getByNormalizedHandle(Handle.normalize(handle), excludeState)
  }

  private val compiledGetByUserId = Compiled { (userId: Column[Id[User]]) =>
    for (row <- rows if row.userId === userId) yield row
  }

  private val compiledGetByUserIdAndExcludedState = Compiled { (userId: Column[Id[User]], excludedState: Column[State[HandleOwnership]]) =>
    for (row <- rows if row.userId === userId && row.state =!= excludedState) yield row
  }

  private val compiledGetByOrganizationId = Compiled { (organizationId: Column[Id[Organization]]) =>
    for (row <- rows if row.organizationId === organizationId) yield row
  }

  private val compiledGetByOrganizationIdAndExcludedState = Compiled { (organizationId: Column[Id[Organization]], excludedState: Column[State[HandleOwnership]]) =>
    for (row <- rows if row.organizationId === organizationId && row.state =!= excludedState) yield row
  }

  private val compiledGetAllOwnedBySystem = Compiled { for (row <- rows if row.organizationId.isEmpty && row.userId.isEmpty) yield row }
  private val compiledGetAllOwnedBySystemAndExcludeState = Compiled { (excludedState: Column[State[HandleOwnership]]) =>
    for (row <- rows if row.organizationId.isEmpty && row.userId.isEmpty && row.state =!= excludedState) yield row
  }

  def getByOwnerId(ownerId: Option[HandleOwner], excludeState: Option[State[HandleOwnership]])(implicit session: RSession): Seq[HandleOwnership] = {
    val q = ownerId match {
      case Some(OrganizationOwner(orgId)) => excludeState.map(compiledGetByOrganizationIdAndExcludedState(orgId, _)) getOrElse compiledGetByOrganizationId(orgId)
      case Some(UserOwner(userId)) => excludeState.map(compiledGetByUserIdAndExcludedState(userId, _)) getOrElse compiledGetByUserId(userId)
      case None => excludeState.map(compiledGetAllOwnedBySystemAndExcludeState(_)) getOrElse compiledGetAllOwnedBySystem
    }
    q.list
  }

  def lock(handle: Handle)(implicit session: RWSession): Boolean = setLock(handle, true)

  def unlock(handle: Handle)(implicit session: RWSession): Boolean = setLock(handle, false)

  private def setLock(handle: Handle, locked: Boolean)(implicit session: RWSession): Boolean = {
    getByHandle(handle).exists { ownership =>
      val mustBeChanged = (ownership.locked != locked)
      if (mustBeChanged) { save(ownership.copy(locked = locked)) }
      mustBeChanged
    }
  }
}
