package com.keepit.model

import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._

class UserEmailAddressRepoTest extends Specification with ShoeboxTestInjector {

  "UserEmailAddressRepo" should {
    "persist and load" in {
      withDb() { implicit injector =>
        val clock = inject[FakeClock]
        val now = clock.now
        val (user, address) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
          userEmailAddressRepo.save(UserEmailAddress.create(user.id.get, EmailAddress("shachaf@gmail.com")).copy(createdAt = now.minusDays(3)))
          val address = userEmailAddressRepo.save(UserEmailAddress.create(user.id.get, EmailAddress("shanee@gmail.com")).copy(createdAt = now.minusDays(1).minusHours(6)))
          userEmailAddressRepo.save(UserEmailAddress.create(user.id.get, EmailAddress("dafna@gmail.com")).copy(createdAt = now.minusHours(3)))
          //should not be shown
          (user, address)
        }

        db.readOnlyMaster { implicit s =>
          val unverified = userEmailAddressRepo.getUnverified(now.minusDays(2), now.minusDays(1))
          unverified.size === 1
          unverified.head === address
        }
        db.readOnlyMaster { implicit s =>
          val unverified = userEmailAddressRepo.getUnverified(now.minusDays(10), now)
          unverified.size === 3
        }
        db.readWrite { implicit s =>
          userEmailAddressRepo.save(address.copy(verifiedAt = Some(now)))
        }
        db.readOnlyMaster { implicit s =>
          val unverified = userEmailAddressRepo.getUnverified(now.minusDays(10), now)
          unverified.size === 2
        }
        db.readOnlyMaster { implicit s =>
          val unverified = userEmailAddressRepo.getUnverified(now.minusHours(1), now)
          unverified.isEmpty === true
        }

        db.readOnlyMaster { implicit s =>
          val emails = Set("shachaf@gmail.com", "shanee@gmail.com", "dafna@gmail.com", "eishay@gmail.com").map(EmailAddress.apply)
          val userByEmail = userEmailAddressRepo.getUsersByAddresses(emails)
          userByEmail === emails.map(email => email -> user.id.get).toMap - EmailAddress("eishay@gmail.com")
        }
      }
    }
  }
}
