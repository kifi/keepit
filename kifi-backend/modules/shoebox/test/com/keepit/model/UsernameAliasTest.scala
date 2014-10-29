package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id

class UsernameAliasTest extends Specification with ShoeboxTestInjector {

  val firstUserId = Id[User](134)
  val secondUserId = Id[User](-134)
  val léo = Username("léo")

  "UsernameAliasRepo" should {

    "alias a normalized username to a single user" in {
      withDb() { implicit injector =>

        val leoAlias = db.readWrite { implicit session => usernameAliasRepo.alias(léo, firstUserId) }
        leoAlias.username.value === "leo"
        leoAlias.userId === firstUserId
        leoAlias.state === UsernameAliasStates.ACTIVE

        db.readWrite { implicit session =>
          usernameAliasRepo.alias(léo, firstUserId) === leoAlias
          usernameAliasRepo.alias(Username("leo"), firstUserId) === leoAlias
          usernameAliasRepo.alias(Username("Leo"), firstUserId) === leoAlias
          usernameAliasRepo.alias(Username("Leo"), firstUserId) === leoAlias
        }

        val updatedAlias = db.readWrite { implicit session =>
          usernameAliasRepo.alias(léo, secondUserId)
        }

        updatedAlias.id.get === leoAlias.id.get
        updatedAlias.userId === secondUserId
      }
    }

    "get active aliases by normalized username" in {
      withDb() { implicit injector =>
        val leoAlias = db.readWrite { implicit session => usernameAliasRepo.alias(léo, firstUserId) }
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
          val reservedAlias = usernameAliasRepo.alias(léo, firstUserId, reserve = true)
          reservedAlias.userId === firstUserId
          reservedAlias.isReserved === true
        }
      }

      "not release a reserved alias implicitly" in {
        db.readWrite { implicit session =>
          val reservedAlias = usernameAliasRepo.getByUsername(léo).get
          usernameAliasRepo.alias(léo, firstUserId, reserve = false) === reservedAlias
        }
      }

      "protect a reserved alias" in {
        db.readWrite { implicit session =>
          val reservedAlias = usernameAliasRepo.getByUsername(léo).get
          usernameAliasRepo.alias(léo, secondUserId, reserve = false) should throwA(ReservedUsernameException(reservedAlias))
          usernameAliasRepo.alias(léo, secondUserId, reserve = true) should throwA(ReservedUsernameException(reservedAlias)) /// a reserved alias must be explicitly released before it can be reserved by someone else
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

          val updatedAlias = usernameAliasRepo.alias(léo, secondUserId)
          updatedAlias.id.get === releasedAlias.id.get
          updatedAlias.userId === secondUserId
        }
      }
    }
  }
}
