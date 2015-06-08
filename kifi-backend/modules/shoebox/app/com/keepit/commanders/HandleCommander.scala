package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.HandleOwnershipStates._
import com.keepit.model._
import com.keepit.common.core._
import com.keepit.common.time._

import scala.util.{ Failure, Success, Try }

object HandleCommander {

  case class InvalidHandleException(handle: Handle) extends Exception(s"Handle $handle is invalid.")

  sealed abstract class UnavailableHandleException(ownership: HandleOwnership, reason: String)
    extends Exception(s"Handle ${ownership.handle} is owned by ${ownership.prettyOwner} ($reason).")

  case class LockedHandleException(ownership: HandleOwnership) extends UnavailableHandleException(ownership, "locked")

  case class ProtectedHandleException(ownership: HandleOwnership) extends UnavailableHandleException(ownership, "protected")

  case class PrimaryHandleException(ownership: HandleOwnership) extends UnavailableHandleException(ownership, "primary")

}

@Singleton
class HandleCommander @Inject() (
    db: Database,
    handleRepo: HandleOwnershipRepo,
    userRepo: UserRepo,
    usernameCache: UsernameCache,
    organizationRepo: OrganizationRepo,
    airbrake: AirbrakeNotifier,
    clock: Clock) extends Logging {

  import HandleCommander._

  def setUsername(username: Username, user: User, lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[User] = {
    claimUsername(username, user.id.get, lock, overrideProtection, overrideValidityCheck).map { normalizedUsername =>
      val newUsername = PrimaryUsername(original = username, normalized = normalizedUsername)
      val updatedUser = userRepo.save(user.copy(primaryUsername = Some(newUsername)))
      user.primaryUsername.foreach { oldUsername =>
        usernameCache.remove(UsernameKey(oldUsername.original)) //we have to do cache invalidation now, the repo does not have the old username for that
        if (oldUsername.original != newUsername.original && user.createdAt.isBefore(clock.now.minusHours(1))) {
          airbrake.notify(s"Username change for user ${user.id.get}. ${oldUsername.original} -> ${newUsername.original}")
        }
      }
      updatedUser
    }
  }

  def claimUsername(username: Username, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[Username] = {
    claimHandle(username, Some(Right(userId)), lock, overrideProtection, overrideValidityCheck).map { ownership =>
      Username(ownership.handle.value)
    }
  }

  private[commanders] def claimHandle(handle: Handle, ownerId: Option[Either[Id[Organization], Id[User]]], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    if (overrideValidityCheck || HandleOps.isValid(handle.value)) setHandleOwnership(handle, ownerId, lock, overrideProtection)
    else Failure(InvalidHandleException(handle))
  } tap {
    case Failure(error) => log.error(s"Failed to claim handle $handle for ${HandleOwnership.prettyOwner(ownerId)}", error)
    case Success(updatedOwnership) => log.info(s"Handle $handle (${updatedOwnership.handle}) is now owned by ${updatedOwnership.prettyOwner}: $updatedOwnership")
  }

  private def setHandleOwnership(handle: Handle, ownerId: Option[Either[Id[Organization], Id[User]]], lock: Boolean = false, overrideProtection: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    val ownershipMaybe = {
      val normalizedHandle = Handle.normalize(handle)
      handleRepo.getByNormalizedHandle(normalizedHandle, excludeState = None) match {
        case Some(ownership) if ownership.belongsTo(ownerId) => Success(ownership)
        case Some(ownership) if ownership.isLocked => Failure(LockedHandleException(ownership))
        case Some(ownership) if ownership.isProtected && !overrideProtection => Failure(ProtectedHandleException(ownership))
        case Some(ownership) if isPrimary(ownership) => Failure(PrimaryHandleException(ownership))
        case Some(availableOwnership) => Success(availableOwnership.copy(state = ACTIVE, locked = false, ownerId = ownerId))
        case None => Success(HandleOwnership(handle = normalizedHandle, ownerId = ownerId))
      }
    }
    ownershipMaybe.map { ownership =>
      handleRepo.save(ownership.copy(lastClaimedAt = clock.now, locked = ownership.locked || lock))
    }
  }

  private def isPrimary(ownership: HandleOwnership)(implicit session: RSession): Boolean = {
    ownership.ownerId.exists {
      case Left(organizationId) => organizationRepo.get(organizationId).handle.exists(_.normalized.value == ownership.handle.value)
      case Right(userId) => userRepo.get(userId).primaryUsername.exists(_.normalized.value == ownership.handle.value)
    }
  }

  def reclaim(handle: Handle, overrideProtection: Boolean = false, overrideLock: Boolean = false)(implicit session: RWSession): Try[Handle] = {
    setHandleOwnership(handle, ownerId = None, overrideProtection = overrideProtection) recoverWith {
      case LockedHandleException(_) if overrideLock =>
        handleRepo.unlock(handle)
        setHandleOwnership(handle, ownerId = None, lock = true, overrideProtection = overrideProtection) tap { _ =>
          handleRepo.lock(handle) // Whether the handle was successfully reclaimed or not, make sure the lock is preserved
        }
    } map (_.handle)
  }

  def reclaimAll(ownerId: Either[Id[Organization], Id[User]], overrideProtection: Boolean = false, overrideLock: Boolean = false)(implicit session: RWSession): Seq[Try[Handle]] = {
    val handles = handleRepo.getByOwnerId(Some(ownerId)).map(_.handle)
    handles.map(reclaim(_, overrideProtection, overrideLock))
  }
}
