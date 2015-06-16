package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
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

@ImplementedBy(classOf[HandleCommanderImpl])
trait HandleCommander {
  def getByHandle(handle: Handle)(implicit session: RSession): Option[(Either[Organization, User], Boolean)]
  def canBeClaimed(handle: Handle, ownerId: Option[HandleOwner], overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RSession): Boolean
  def reclaim(handle: Handle, overrideProtection: Boolean = false, overrideLock: Boolean = false)(implicit session: RWSession): Try[Handle]
  def reclaimAll(ownerId: HandleOwner, overrideProtection: Boolean = false, overrideLock: Boolean = false)(implicit session: RWSession): Seq[Try[Handle]]

  def autoSetUsername(user: User)(implicit session: RWSession): Option[User]
  def setUsername(user: User, username: Username, lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[User]
  def claimUsername(username: Username, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[Username]

  def autoSetOrganizationHandle(organization: Organization)(implicit session: RWSession): Option[Organization]
  def setOrganizationHandle(organization: Organization, orgHandle: OrganizationHandle, lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[Organization]
  def claimOrganizationHandle(orgHandle: OrganizationHandle, organizationId: Id[Organization], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[OrganizationHandle]
}

@Singleton
class HandleCommanderImpl @Inject() (
    db: Database,
    handleRepo: HandleOwnershipRepo,
    userRepo: UserRepo,
    usernameCache: UsernameCache,
    organizationRepo: OrganizationRepo,
    airbrake: AirbrakeNotifier,
    clock: Clock) extends HandleCommander with Logging {

  import HandleCommander._

  def getByHandle(handle: Handle)(implicit session: RSession): Option[(Either[Organization, User], Boolean)] = {
    handleRepo.getByHandle(handle).flatMap { ownership =>
      ownership.ownerId.map {
        case HandleOwner.OrganizationOwner(organizationId) => {
          val organization = organizationRepo.get(organizationId)
          (Left(organization), isPrimaryOwner(ownership, organization))
        }
        case HandleOwner.UserOwner(userId) => {
          val user = userRepo.get(userId)
          (Right(user), isPrimaryOwner(ownership, user))
        }
      }
    }
  }

  def canBeClaimed(handle: Handle, ownerId: Option[HandleOwner], overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RSession): Boolean = {
    getValidOwnership(handle, ownerId, overrideProtection = overrideProtection, overrideValidityCheck = overrideValidityCheck).isSuccess
  }

  def reclaim(handle: Handle, overrideProtection: Boolean = false, overrideLock: Boolean = false)(implicit session: RWSession): Try[Handle] = {
    getAvailableOwnership(handle, ownerId = None, overrideProtection = overrideProtection).map(claimOwnership(_).handle) recoverWith {
      case LockedHandleException(_) if overrideLock => {
        handleRepo.unlock(handle)
        reclaim(handle, overrideProtection, overrideLock = false) tap { _ =>
          handleRepo.lock(handle) // Whether the handle was successfully reclaimed or not, make sure the lock is preserved
        }
      }
    }
  }

  def reclaimAll(ownerId: HandleOwner, overrideProtection: Boolean = false, overrideLock: Boolean = false)(implicit session: RWSession): Seq[Try[Handle]] = {
    val handles = handleRepo.getByOwnerId(Some(ownerId)).map(_.handle)
    handles.map(reclaim(_, overrideProtection = overrideProtection, overrideLock = overrideLock))
  }

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
          airbrake.notify(s"Username change for user $userId: ${oldUsername.original} -> ${newUsername.original}")
        }
      }
      updatedUser
    }
  }

  def claimUsername(username: Username, userId: Id[User], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[Username] = {
    claimHandle(username, Some(userId), lock, overrideProtection, overrideValidityCheck).map { ownership =>
      Username(ownership.handle.value)
    }
  }

  def autoSetOrganizationHandle(organization: Organization)(implicit session: RWSession): Option[Organization] = {
    val candidates = generateHandleCandidates(Left(organization.name)).map { case Handle(handle) => OrganizationHandle(handle) }
    candidates.toStream.map(setOrganizationHandle(organization, _)).collectFirst {
      case Success(orgWithHandle) => orgWithHandle
    } tap { orgWithHandleOpt =>
      if (orgWithHandleOpt.isEmpty) {
        val message = s"could not find a decent username for organization $organization, tried the following candidates: $candidates"
        log.warn(message)
        airbrake.notify(message)
      }
    }
  }

  def setOrganizationHandle(organization: Organization, orgHandle: OrganizationHandle, lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[Organization] = {
    val organizationId = organization.id.get
    claimOrganizationHandle(orgHandle, organizationId, lock, overrideProtection, overrideValidityCheck).map { normalizedOrgHandle =>
      val newOrgHandle = PrimaryOrganizationHandle(original = orgHandle, normalized = normalizedOrgHandle)
      val updatedOrganization = organizationRepo.save(organization.copy(handle = Some(newOrgHandle)))
      organization.handle.foreach { oldOrgHandle =>
        claimOrganizationHandle(oldOrgHandle.original, organizationId, overrideValidityCheck = true).get // Claiming old handle again to reset grace protection period
        if (oldOrgHandle.original != newOrgHandle.original && organization.createdAt.isBefore(clock.now.minusHours(1))) {
          airbrake.notify(s"Handle change for organization $organizationId: ${oldOrgHandle.original} -> ${newOrgHandle.original}")
        }
      }
      updatedOrganization
    }
  }

  def claimOrganizationHandle(orgHandle: OrganizationHandle, organizationId: Id[Organization], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[OrganizationHandle] = {
    claimHandle(orgHandle, Some(organizationId), lock, overrideProtection, overrideValidityCheck).map { ownership =>
      OrganizationHandle(ownership.handle.value)
    }
  }

  private[commanders] def claimHandle(handle: Handle, ownerId: Option[HandleOwner], lock: Boolean = false, overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RWSession): Try[HandleOwnership] = {
    getValidOwnership(handle, ownerId, overrideProtection = overrideProtection, overrideValidityCheck = overrideValidityCheck).map(claimOwnership(_, lock))
  } tap {
    case Failure(error) => log.error(s"Failed to claim handle $handle for ${HandleOwner.prettyPrint(ownerId)}", error)
    case Success(updatedOwnership) => log.info(s"Handle $handle (${updatedOwnership.handle}) is now owned by ${updatedOwnership.prettyOwner}: $updatedOwnership")
  }

  private def claimOwnership(ownership: HandleOwnership, lock: Boolean = false)(implicit session: RWSession): HandleOwnership = {
    handleRepo.save(ownership.copy(lastClaimedAt = clock.now, locked = ownership.locked || lock))
  }

  private def getValidOwnership(handle: Handle, ownerId: Option[HandleOwner], overrideProtection: Boolean = false, overrideValidityCheck: Boolean = false)(implicit session: RSession): Try[HandleOwnership] = {
    if (overrideValidityCheck || HandleOps.isValid(handle.value)) getAvailableOwnership(handle, ownerId, overrideProtection)
    else Failure(InvalidHandleException(handle))
  }

  private def getAvailableOwnership(handle: Handle, ownerId: Option[HandleOwner], overrideProtection: Boolean = false)(implicit session: RSession): Try[HandleOwnership] = {
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

  private def isPrimary(ownership: HandleOwnership)(implicit session: RSession): Boolean = {
    ownership.ownerId.exists {
      case HandleOwner.OrganizationOwner(organizationId) => isPrimaryOwner(ownership, organizationRepo.get(organizationId))
      case HandleOwner.UserOwner(userId) => isPrimaryOwner(ownership, userRepo.get(userId))
    }
  }

  private def isPrimaryOwner(ownership: HandleOwnership, organization: Organization): Boolean = organization.handle.exists(_.normalized.value == ownership.handle.value)
  private def isPrimaryOwner(ownership: HandleOwnership, user: User): Boolean = user.primaryUsername.exists(_.normalized.value == ownership.handle.value)

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
