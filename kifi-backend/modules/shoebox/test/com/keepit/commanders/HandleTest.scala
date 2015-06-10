package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.commanders.HandleCommander._
import com.keepit.common.db.Id
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.util.{ Try, Success, Failure }

class HandleTest extends Specification with ShoeboxTestInjector {

  implicit def fromUserIdtoOwnerId(userId: Id[User]): Option[HandleOwner] = Some(userId)

  private def checkOwnership(expectedOwnership: HandleOwnership, ownership: HandleOwnership) = {
    ownership.id.get === expectedOwnership.id.get
    ownership.handle === expectedOwnership.handle
    ownership.ownerId === expectedOwnership.ownerId
    ownership.state === expectedOwnership.state
    ownership.lastClaimedAt isAfter expectedOwnership.lastClaimedAt should beTrue
  }

  val léo = Username("léo")

  private def initUsers()(implicit injector: Injector): (Id[User], Id[User], Id[User]) = {
    db.readWrite { implicit session =>
      (UserFactory.user().saved.id.get,
        UserFactory.user().saved.id.get,
        UserFactory.user().saved.id.get)
    }
  }

  "HandleCommander" should {

    "allocate a normalized username to a unique user" in {
      withDb() { implicit injector =>

        val (firstUserId, secondUserId, _) = initUsers()

        val leoOwnership = db.readWrite { implicit session => handleCommander.claimHandle(léo, firstUserId).get }
        leoOwnership.handle.value === "leo"
        leoOwnership.belongsToUser(firstUserId) should beTrue
        leoOwnership.state === HandleOwnershipStates.ACTIVE

        db.readWrite { implicit session =>
          checkOwnership(leoOwnership, handleCommander.claimHandle(léo, firstUserId).get)
          checkOwnership(leoOwnership, handleCommander.claimHandle(Username("leo"), firstUserId).get)
          checkOwnership(leoOwnership, handleCommander.claimHandle(Username("Leo"), firstUserId).get)
          checkOwnership(leoOwnership, handleCommander.claimHandle(Username("Leo"), firstUserId).get)
        }

        val updatedOwnership = db.readWrite { implicit session =>
          handleCommander.claimHandle(léo, secondUserId, overrideProtection = true).get // recent username protection is enabled by default
        }

        updatedOwnership.id.get === leoOwnership.id.get
        updatedOwnership.belongsToUser(secondUserId) should beTrue
        updatedOwnership.lastClaimedAt isAfter leoOwnership.lastClaimedAt should beTrue
      }
    }

    "get active ownerships by normalized username" in {
      withDb() { implicit injector =>

        val (firstUserId, secondUserId, _) = initUsers()

        val leoOwnership = db.readWrite { implicit session => handleCommander.claimHandle(léo, firstUserId).get }
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

        val (firstUserId, secondUserId, _) = initUsers()

        db.readWrite { implicit session =>
          val protectedOwnership = handleCommander.claimHandle(léo, firstUserId).get
          protectedOwnership.isProtected === true
          handleCommander.claimHandle(léo, secondUserId) === Failure(ProtectedHandleException(protectedOwnership))
          handleCommander.claimHandle(léo, secondUserId, lock = true) === Failure(ProtectedHandleException(protectedOwnership))
        }
      }
    }

    "lock an ownership" in {
      withDb() { implicit injector =>

        val (firstUserId, secondUserId, _) = initUsers()

        db.readWrite { implicit session =>
          val lockedOwnership = handleCommander.claimHandle(léo, firstUserId, lock = true).get
          lockedOwnership.belongsToUser(firstUserId)
          lockedOwnership.isLocked === true
        }
      }
    }

    "not release a locked ownership implicitly" in {
      withDb() { implicit injector =>

        val (firstUserId, secondUserId, _) = initUsers()

        db.readWrite { implicit session => handleCommander.claimHandle(léo, firstUserId, lock = true).get }
        db.readWrite { implicit session =>
          val lockedOwnership = handleRepo.getByHandle(léo).get
          checkOwnership(lockedOwnership, handleCommander.claimHandle(léo, firstUserId, lock = false).get)
        }
      }
    }

    "protect a locked ownership" in {
      withDb() { implicit injector =>

        val (firstUserId, secondUserId, _) = initUsers()

        db.readWrite { implicit session => handleCommander.claimHandle(léo, firstUserId, lock = true).get }
        db.readWrite { implicit session =>
          val lockedOwnership = handleRepo.getByHandle(léo).get
          handleCommander.claimHandle(léo, secondUserId, lock = false) === Failure(LockedHandleException(lockedOwnership))
          handleCommander.claimHandle(léo, secondUserId, lock = true) === Failure(LockedHandleException(lockedOwnership)) /// a locked ownership must be explicitly released before it can be locked by someone else
        }
      }
    }

    "release a locked ownership" in {
      withDb() { implicit injector =>

        val (firstUserId, secondUserId, _) = initUsers()

        db.readWrite { implicit session => handleCommander.claimHandle(léo, firstUserId, lock = true).get }
        db.readWrite { implicit session =>
          val reservedOwnership = handleRepo.getByHandle(léo).get

          handleRepo.unlock(léo) === true
          handleRepo.unlock(léo) === false

          val releasedOwnership = handleRepo.getByHandle(léo).get
          releasedOwnership.id.get === reservedOwnership.id.get
          releasedOwnership.isLocked === false
          releasedOwnership.lastClaimedAt === reservedOwnership.lastClaimedAt

          val updatedOwnership = handleCommander.claimHandle(léo, secondUserId, overrideProtection = true).get // recent username protection is enabled by default
          updatedOwnership.id.get === releasedOwnership.id.get
          updatedOwnership.belongsToUser(secondUserId) should beTrue
          updatedOwnership.lastClaimedAt isAfter releasedOwnership.lastClaimedAt should beTrue
        }
      }
    }

    "normalize usernames" in {
      HandleOps.normalize("léo") === "leo"
      HandleOps.normalize("andrew.conner2") === "andrewconner2"
      HandleOps.normalize("康弘康弘") === "康弘康弘"
      HandleOps.normalize("ân_dréw-c.ön.nér") === "andrewconner"
      HandleOps.normalize("bob1234") === "bob1234"
      HandleOps.normalize("123bob1234") === "123bob1234"
    }

    "allow change of username" in {
      withDb() { implicit injector =>
        val (firstUserId, secondUserId, thirdUserId) = initUsers()

        def setUsername(userId: Id[User], username: String): Username = {
          db.readWrite(attempts = 3) { implicit session =>
            val user = userRepo.get(userId)
            handleCommander.setUsername(user, Username(username)).map(_.username).get
          }
        }

        // basic changing, no dupes
        setUsername(firstUserId, "bobz") === Username("bobz")
        setUsername(secondUserId, "bob.z") should throwAn[UnavailableHandleException]
        setUsername(firstUserId, "bob.z") === Username("bob.z")

        // changes user model
        db.readOnlyMaster(s => userRepo.get(secondUserId)(s).username.value) !== "obama"
        setUsername(secondUserId, "obama") === Username("obama")
        db.readOnlyMaster(s => userRepo.get(secondUserId)(s).username) === Username("obama")

        // filter out invalid names
        setUsername(thirdUserId, "a.-bc") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, ".abc3") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "kifisupport") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "mayihelpyou") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "abcd?") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "abcd?") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "abcd?") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "amazon") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "yes") should throwAn[InvalidHandleException]
        setUsername(thirdUserId, "aes.corp") should throwAn[InvalidHandleException]

      }
    }
  }
}
