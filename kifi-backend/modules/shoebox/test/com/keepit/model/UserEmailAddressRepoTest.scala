package com.keepit.model

import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class UserEmailAddressRepoTest extends Specification with ShoeboxTestInjector {

  "UserEmailAddressRepo" should {
    "persist and load" in {
      withDb() { implicit injector =>
        val (user, address) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
          val address = userEmailAddressRepo.save(UserEmailAddress(userId = user.id.get, address = EmailAddress("shanee@gmail.com")))
          (user, address)
        }
        db.readOnlyMaster { implicit s =>
          val now = inject[FakeClock].now
          val unverified = userEmailAddressRepo.getUnverified(now.minusDays(1), now)
          unverified.size === 1
          unverified.head === address
        }
        db.readWrite { implicit s =>
          userEmailAddressRepo.save(address.withState(UserEmailAddressStates.VERIFIED))
        }
        db.readOnlyMaster { implicit s =>
          val now = inject[FakeClock].now
          val unverified = userEmailAddressRepo.getUnverified(now.minusDays(1), now)
          unverified.isEmpty === true
        }
      }
    }
  }
}
