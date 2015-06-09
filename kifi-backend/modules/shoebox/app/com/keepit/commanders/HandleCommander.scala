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
import org.apache.commons.lang3.RandomStringUtils
import scala.util.{ Either, Failure, Success, Try }

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

  def autoSetUsername(user: User)(implicit session: RWSession): Option[User] = {
    val candidates = generateHandleCandidates(Right((user.firstName, user.lastName))).map { case Handle(handle) => Username(handle) }
    candidates.toStream.map(setUsername(user, _)).collectFirst {
      case Success(userWithUsername) => userWithUsername
    } tap { userWithUsernameOpt =>
      if (userWithUsernameOpt.isEmpty) {
        val message = s"could not find a decent username for user $user, tried the following candidates: $candidates"
        log.warn(message)
        airbrake.notify(message)
      }
    }
  }

  def setUsername(user: User, username: Username, lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[User] = {
    val userId = user.id.get
    claimUsername(username, userId, lock, overrideProtection, overrideValidityCheck).map { normalizedUsername =>
      val newUsername = PrimaryUsername(original = username, normalized = normalizedUsername)
      val updatedUser = userRepo.save(user.copy(primaryUsername = Some(newUsername)))
      user.primaryUsername.foreach { oldUsername =>
        claimUsername(oldUsername.original, userId, overrideValidityCheck = true).get // Claiming old username again to reset grace protection period
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
      case Left(organizationId) => isPrimaryOwner(ownership, organizationRepo.get(organizationId))
      case Right(userId) => isPrimaryOwner(ownership, userRepo.get(userId))
    }
  }

  private def isPrimaryOwner(ownership: HandleOwnership, organization: Organization): Boolean = organization.handle.exists(_.normalized.value == ownership.handle.value)
  private def isPrimaryOwner(ownership: HandleOwnership, user: User): Boolean = user.primaryUsername.exists(_.normalized.value == ownership.handle.value)

  def getByHandle(handle: Handle)(implicit session: RSession): Option[(Either[Organization, User], Boolean)] = {
    handleRepo.getByHandle(handle).flatMap { ownership =>
      ownership.ownerId.map {
        case Left(organizationId) => {
          val organization = organizationRepo.get(organizationId)
          (Left(organization), isPrimaryOwner(ownership, organization))
        }
        case Right(userId) => {
          val user = userRepo.get(userId)
          (Right(user), isPrimaryOwner(ownership, user))
        }
      }
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

  private def generateHandleCandidates(rawName: Either[String, (String, String)]): Stream[Handle] = {
    val name = rawName match {
      case Left(rawFullName) => HandleOps.lettersOnly(rawFullName.trim).take(30).toLowerCase
      case Right((rawFirstName, rawLastName)) => {
        val firstName = HandleOps.lettersOnly(rawFirstName.trim).take(15).toLowerCase
        val lastName = HandleOps.lettersOnly(rawLastName.trim).take(15).toLowerCase
        if (firstName.isEmpty) lastName
        else if (lastName.isEmpty) firstName
        else s"$firstName-$lastName"
      }
    }

    val seed = if (name.length < 4) {
      val filler = Seq.fill(4 - name.length)(0)
      s"$name-$filler"
    } else name
    def randomNumber = scala.util.Random.nextInt(999)
    val censorList = HandleOps.censorList.mkString("|")
    val preCandidates = Stream(seed) ++ (1 to 30).toStream.map(_ => s"$seed-$randomNumber") ++ (10 to 20).toStream.map(n => RandomStringUtils.randomAlphanumeric(n))
    val candidates = preCandidates.map { name =>
      log.info(s"validating handle $name for $rawName")
      val valid = if (HandleOps.isValid(name)) name else name.replaceAll(censorList, s"C${randomNumber}C")
      log.info(s"handle $name is valid")
      valid
    }.filter(HandleOps.isValid)
    if (candidates.isEmpty) throw new Exception(s"Could not create handle candidates for $rawName")
    candidates map { c => Handle(c) }
  }
}
