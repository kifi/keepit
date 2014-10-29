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
          usernameAliasRepo.alias(léo, secondUserId).get
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

    withDb() { implicit injector =>

      "reserve an alias" in {
        db.readWrite { implicit session =>
          val reservedAlias = usernameAliasRepo.alias(léo, firstUserId, reserve = true).get
          reservedAlias.userId === firstUserId
          reservedAlias.isReserved === true
        }
      }

      "not release a reserved alias implicitly" in {
        db.readWrite { implicit session =>
          val reservedAlias = usernameAliasRepo.getByUsername(léo).get
          checkAlias(reservedAlias, usernameAliasRepo.alias(léo, firstUserId, reserve = false).get)
        }
      }

      "protect a reserved alias" in {
        db.readWrite { implicit session =>
          val reservedAlias = usernameAliasRepo.getByUsername(léo).get
          usernameAliasRepo.alias(léo, secondUserId, reserve = false) === Failure(ReservedUsernameException(reservedAlias))
          usernameAliasRepo.alias(léo, secondUserId, reserve = true) === Failure(ReservedUsernameException(reservedAlias)) /// a reserved alias must be explicitly released before it can be reserved by someone else
        }
      }

      "release a reserved alias" in {
        db.readWrite { implicit session =>
          val reservedAlias = usernameAliasRepo.getByUsername(léo).get

          usernameAliasRepo.release(léo) === true
          usernameAliasRepo.release(léo) === false

          val releasedAlias = usernameAliasRepo.getByUsername(léo).get
          releasedAlias.id.get === reservedAlias.id.get
          releasedAlias.isReserved === false
          releasedAlias.lastActivatedAt === reservedAlias.lastActivatedAt

          val updatedAlias = usernameAliasRepo.alias(léo, secondUserId).get
          updatedAlias.id.get === releasedAlias.id.get
          updatedAlias.userId === secondUserId
          updatedAlias.lastActivatedAt isAfter releasedAlias.lastActivatedAt should beTrue
        }
      }
    }
  }
}
