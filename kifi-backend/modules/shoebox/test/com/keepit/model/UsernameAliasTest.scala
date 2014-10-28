package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id

class UsernameAliasTest extends Specification with ShoeboxTestInjector {

  val firstUserId = Id[User](134)
  val secondUserId = Id[User](-134)
  val léo = Username("léo")

  "UsernameAliasRepo" should {

    "intern aliases by normalized username" in {
      withDb() { implicit injector =>

        val leoAlias = db.readWrite { implicit session => usernameAliasRepo.intern(léo, firstUserId) }
        leoAlias.username.value === "leo"
        leoAlias.userId === firstUserId
        leoAlias.state === UsernameAliasStates.ACTIVE

        db.readWrite { implicit session =>
          usernameAliasRepo.intern(léo, firstUserId) === leoAlias
          usernameAliasRepo.intern(Username("leo"), firstUserId) === leoAlias
          usernameAliasRepo.intern(Username("Leo"), firstUserId) === leoAlias
          usernameAliasRepo.intern(Username("Leo"), firstUserId) === leoAlias
        }

        val updatedAlias = db.readWrite { implicit session =>
          usernameAliasRepo.intern(léo, secondUserId)
        }

        updatedAlias.id.get === leoAlias.id.get
        updatedAlias.userId === secondUserId
      }
    }

    "get active aliases by normalized username" in {
      withDb() { implicit injector =>
        val leoAlias = db.readWrite { implicit session => usernameAliasRepo.intern(léo, firstUserId) }
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

    "reserve and release aliases" in {
      withDb() { implicit injector =>

        val reservedAlias = db.readWrite { implicit session =>
          usernameAliasRepo.reserve(léo, firstUserId)
          usernameAliasRepo.getByUsername(léo).get
        }
        reservedAlias.userId === firstUserId
        reservedAlias.isReserved === true

        val releasedAlias = db.readWrite { implicit session =>
          usernameAliasRepo.intern(léo, secondUserId) should throwA(ReservedUsernameException(reservedAlias))
          usernameAliasRepo.reserve(léo, secondUserId) should throwA(ReservedUsernameException(reservedAlias))
          usernameAliasRepo.release(léo) === true
          usernameAliasRepo.getByUsername(léo).get.isReserved === false
          usernameAliasRepo.intern(léo, secondUserId)
        }

        releasedAlias.id.get === reservedAlias.id.get
        releasedAlias.userId === secondUserId
      }
    }
  }
}
