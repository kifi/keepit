package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id
import scala.util.Failure

class UsernameAliasTest extends Specification with ShoeboxTestInjector {

  private def checkAlias(expectedAlias: UsernameAlias, alias: UsernameAlias) = {
    alias.id.get === expectedAlias.id.get
    alias.username === expectedAlias.username
    alias.userId === expectedAlias.userId
    alias.state === expectedAlias.state
    alias.lastActivatedAt isAfter expectedAlias.lastActivatedAt should beTrue
  }

  val firstUserId = Id[User](134)
  val secondUserId = Id[User](-134)
  val léo = Username("léo")

  "UsernameAliasRepo" should {

    "alias a normalized username to a single user" in {
      withDb() { implicit injector =>

        val leoAlias = db.readWrite { implicit session => usernameAliasRepo.alias(léo, firstUserId).get }
        leoAlias.username.value === "leo"
        leoAlias.userId === firstUserId
        leoAlias.state === UsernameAliasStates.ACTIVE

        db.readWrite { implicit session =>
          checkAlias(leoAlias, usernameAliasRepo.alias(léo, firstUserId).get)
          checkAlias(leoAlias, usernameAliasRepo.alias(Username("leo"), firstUserId).get)
          checkAlias(leoAlias, usernameAliasRepo.alias(Username("Leo"), firstUserId).get)
          checkAlias(leoAlias, usernameAliasRepo.alias(Username("Leo"), firstUserId).get)
        }

        val updatedAlias = db.readWrite { implicit session =>
          usernameAliasRepo.alias(léo, secondUserId, overrideProtection = true).get // recent username protection is enabled by default
        }

        updatedAlias.id.get === leoAlias.id.get
        updatedAlias.userId === secondUserId
        updatedAlias.lastActivatedAt isAfter leoAlias.lastActivatedAt should beTrue
      }
    }

    "get active aliases by normalized username" in {
      withDb() { implicit injector =>
        val leoAlias = db.readWrite { implicit session => usernameAliasRepo.alias(léo, firstUserId).get }
        db.readOnlyMaster { implicit session =>
          usernameAliasRepo.getByUsername(léo) === Some(leoAlias)
          usernameAliasRepo.getByUsername(Username("leo")) === Some(leoAlias)
          usernameAliasRepo.getByUsername(Username("Leo")) === Some(leoAlias)
          usernameAliasRepo.getByUsername(Username("Leo")) === Some(leoAlias)
        }

        db.readWrite { implicit session => usernameAliasRepo.save(leoAlias.copy(state = UsernameAliasStates.INACTIVE)) }
        db.readOnlyMaster { implicit session =>
          usernameAliasRepo.getByUsername(léo) === None
          usernameAliasRepo.getByUsername(Username("leo")) === None
          usernameAliasRepo.getByUsername(Username("Leo")) === None
          usernameAliasRepo.getByUsername(Username("Leo")) === None
        }
      }
    }

    "protect a recent alias by default" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          val protectedAlias = usernameAliasRepo.alias(léo, firstUserId).get
          protectedAlias.isProtected === true
          usernameAliasRepo.alias(léo, secondUserId) === Failure(ProtectedUsernameException(protectedAlias))
          usernameAliasRepo.alias(léo, secondUserId, lock = true) === Failure(ProtectedUsernameException(protectedAlias))
        }
      }
    }
    "lock an alias" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          val lockedAlias = usernameAliasRepo.alias(léo, firstUserId, lock = true).get
          lockedAlias.userId === firstUserId
          lockedAlias.isLocked === true
        }
      }
    }

    "not release a locked alias implicitly" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          db.readWrite { implicit session => usernameAliasRepo.alias(léo, firstUserId).get }
          val lockedAlias = usernameAliasRepo.getByUsername(léo).get
          checkAlias(lockedAlias, usernameAliasRepo.alias(léo, firstUserId, lock = false).get)
        }
      }
    }

    //todo(leo): fix test
    //    "protect a locked alias" in {
    //      withDb() { implicit injector =>
    //        db.readWrite { implicit session =>
    //          val lockedAlias = usernameAliasRepo.getByUsername(léo).get
    //          usernameAliasRepo.alias(léo, secondUserId, lock = false) === Failure(LockedUsernameException(lockedAlias))
    //          usernameAliasRepo.alias(léo, secondUserId, lock = true) === Failure(LockedUsernameException(lockedAlias)) /// a locked alias must be explicitly released before it can be locked by someone else
    //        }
    //      }
    //    }

    //todo(leo): fix test
    //    "release a locked alias" in {
    //      withDb() { implicit injector =>
    //        db.readWrite { implicit session =>
    //          val reservedAlias = usernameAliasRepo.getByUsername(léo).get
    //
    //          usernameAliasRepo.unlock(léo) === true
    //          usernameAliasRepo.unlock(léo) === false
    //
    //          val releasedAlias = usernameAliasRepo.getByUsername(léo).get
    //          releasedAlias.id.get === reservedAlias.id.get
    //          releasedAlias.isLocked === false
    //          releasedAlias.lastActivatedAt === reservedAlias.lastActivatedAt
    //
    //          val updatedAlias = usernameAliasRepo.alias(léo, secondUserId, overrideProtection = true).get // recent username protection is enabled by default
    //          updatedAlias.id.get === releasedAlias.id.get
    //          updatedAlias.userId === secondUserId
    //          updatedAlias.lastActivatedAt isAfter releasedAlias.lastActivatedAt should beTrue
    //        }
    //      }
    //    }
  }
}
