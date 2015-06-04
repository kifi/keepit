package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.util.Failure

class HandleOwnershipTest extends Specification with ShoeboxTestInjector {

  private def checkOwnership(expectedOwnership: HandleOwnership, ownership: HandleOwnership) = {
    ownership.id.get === expectedOwnership.id.get
    ownership.handle === expectedOwnership.handle
    ownership.ownerId === expectedOwnership.ownerId
    ownership.state === expectedOwnership.state
    ownership.lastClaimedAt isAfter expectedOwnership.lastClaimedAt should beTrue
  }

  val firstUserId = Id[User](134)
  val secondUserId = Id[User](-134)
  val léo = Username("léo")

  "HandleOwnershipRepo" should {

    "allocate a normalized username to a unique user" in {
      withDb() { implicit injector =>

        val leoOwnership = db.readWrite { implicit session => handleRepo.setUserOwnership(léo, firstUserId).get }
        leoOwnership.handle.value === "leo"
        leoOwnership.belongsToUser(firstUserId) should beTrue
        leoOwnership.state === HandleOwnershipStates.ACTIVE

        db.readWrite { implicit session =>
          checkOwnership(leoOwnership, handleRepo.setUserOwnership(léo, firstUserId).get)
          checkOwnership(leoOwnership, handleRepo.setUserOwnership(Username("leo"), firstUserId).get)
          checkOwnership(leoOwnership, handleRepo.setUserOwnership(Username("Leo"), firstUserId).get)
          checkOwnership(leoOwnership, handleRepo.setUserOwnership(Username("Leo"), firstUserId).get)
        }

        val updatedOwnership = db.readWrite { implicit session =>
          handleRepo.setUserOwnership(léo, secondUserId, overrideProtection = true).get // recent username protection is enabled by default
        }

        updatedOwnership.id.get === leoOwnership.id.get
        updatedOwnership.belongsToUser(secondUserId) should beTrue
        updatedOwnership.lastClaimedAt isAfter leoOwnership.lastClaimedAt should beTrue
      }
    }

    "get active ownershipes by normalized username" in {
      withDb() { implicit injector =>
        val leoOwnership = db.readWrite { implicit session => handleRepo.setUserOwnership(léo, firstUserId).get }
        db.readOnlyMaster { implicit session =>
          handleRepo.getByHandle(léo) === Some(leoOwnership)
          handleRepo.getByHandle(Username("leo")) === Some(leoOwnership)
          handleRepo.getByHandle(Username("Leo")) === Some(leoOwnership)
          handleRepo.getByHandle(Username("Leo")) === Some(leoOwnership)
        }

        db.readWrite { implicit session => handleRepo.save(leoOwnership.copy(state = HandleOwnershipStates.INACTIVE)) }
        db.readOnlyMaster { implicit session =>
          handleRepo.getByHandle(léo) === None
          handleRepo.getByHandle(Username("leo")) === None
          handleRepo.getByHandle(Username("Leo")) === None
          handleRepo.getByHandle(Username("Leo")) === None
        }
      }
    }

    "protect a recent ownership by default" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          val protectedOwnership = handleRepo.setUserOwnership(léo, firstUserId).get
          protectedOwnership.isProtected === true
          handleRepo.setUserOwnership(léo, secondUserId) === Failure(ProtectedHandleException(protectedOwnership))
          handleRepo.setUserOwnership(léo, secondUserId, lock = true) === Failure(ProtectedHandleException(protectedOwnership))
        }
      }
    }
    "lock an ownership" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          val lockedOwnership = handleRepo.setUserOwnership(léo, firstUserId, lock = true).get
          lockedOwnership.belongsToUser(firstUserId)
          lockedOwnership.isLocked === true
        }
      }
    }

    "not release a locked ownership implicitly" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session => handleRepo.setUserOwnership(léo, firstUserId, lock = true).get }
        db.readWrite { implicit session =>
          val lockedOwnership = handleRepo.getByHandle(léo).get
          checkOwnership(lockedOwnership, handleRepo.setUserOwnership(léo, firstUserId, lock = false).get)
        }
      }
    }

    "protect a locked ownership" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session => handleRepo.setUserOwnership(léo, firstUserId, lock = true).get }
        db.readWrite { implicit session =>
          val lockedOwnership = handleRepo.getByHandle(léo).get
          handleRepo.setUserOwnership(léo, secondUserId, lock = false) === Failure(LockedHandleException(lockedOwnership))
          handleRepo.setUserOwnership(léo, secondUserId, lock = true) === Failure(LockedHandleException(lockedOwnership)) /// a locked ownership must be explicitly released before it can be locked by someone else
        }
      }
    }

    "release a locked ownership" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session => handleRepo.setUserOwnership(léo, firstUserId, lock = true).get }
        db.readWrite { implicit session =>
          val reservedOwnership = handleRepo.getByHandle(léo).get

          handleRepo.unlock(léo) === true
          handleRepo.unlock(léo) === false

          val releasedOwnership = handleRepo.getByHandle(léo).get
          releasedOwnership.id.get === reservedOwnership.id.get
          releasedOwnership.isLocked === false
          releasedOwnership.lastClaimedAt === reservedOwnership.lastClaimedAt

          val updatedOwnership = handleRepo.setUserOwnership(léo, secondUserId, overrideProtection = true).get // recent username protection is enabled by default
          updatedOwnership.id.get === releasedOwnership.id.get
          updatedOwnership.belongsToUser(secondUserId) should beTrue
          updatedOwnership.lastClaimedAt isAfter releasedOwnership.lastClaimedAt should beTrue
        }
      }
    }
  }
}
