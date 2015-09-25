package com.keepit.commanders

import com.keepit.common.core._
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ UserValueName, UserFactory, UserValueRepo }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._

class UserEmailAddressCommanderTest extends Specification with ShoeboxTestInjector {

  val vampireEmail = EmailAddress("vampireXslayer@gmail.com")
  val abeEmail = EmailAddress("uncleabe@gmail.com")

  "UserEmailAddressCommander" should {
    "work" in withDb() { implicit injector =>
      {
        db.readWrite { implicit session =>
          val userId = UserFactory.user().withName("Abe", "Lincoln").withUsername("AbeLincoln").saved.id.get

          // intern emails
          userEmailAddressCommander.intern(userId, vampireEmail) should beASuccessfulTry
          userEmailAddressCommander.intern(userId, abeEmail) should beASuccessfulTry

          userEmailAddressRepo.getAllByUser(userId).map(_.address) === Seq(vampireEmail, abeEmail)

          // set an email as pending primary
          userValueRepo.getUserValue(userId, UserValueName.PENDING_PRIMARY_EMAIL) should beNone

          userEmailAddressRepo.getByAddressAndUser(userId, vampireEmail).get tap { vampireRecord =>
            vampireRecord.verified should beFalse
            vampireRecord.primary should beFalse
            userEmailAddressCommander.setAsPrimaryEmail(vampireRecord)
          }
          userValueRepo.getUserValue(userId, UserValueName.PENDING_PRIMARY_EMAIL).map(_.value) should beSome(vampireEmail.address)

          // set an email as primary after verification
          userEmailAddressRepo.getByAddressAndUser(userId, vampireEmail).get tap { vampireRecord =>
            vampireRecord.verified should beFalse
            vampireRecord.primary should beFalse
            userEmailAddressCommander.saveAsVerified(vampireRecord)
          }
          userValueRepo.getUserValue(userId, UserValueName.PENDING_PRIMARY_EMAIL) should beNone
          userEmailAddressRepo.getByAddressAndUser(userId, vampireEmail).get tap { vampireRecord =>
            vampireRecord.verified should beTrue
            vampireRecord.primary should beTrue
          }

          // verify another email
          userEmailAddressRepo.getByAddressAndUser(userId, abeEmail).get tap { abeRecord =>
            abeRecord.verified should beFalse
            abeRecord.primary should beFalse
            userEmailAddressCommander.saveAsVerified(abeRecord)
          }
          userValueRepo.getUserValue(userId, UserValueName.PENDING_PRIMARY_EMAIL) should beNone
          userEmailAddressRepo.getByAddressAndUser(userId, abeEmail).get tap { abeRecord =>
            abeRecord.verified should beTrue
            abeRecord.primary should beFalse
          }

          // remove emails
          userEmailAddressRepo.getByAddressAndUser(userId, vampireEmail).get tap { vampireRecord =>
            userEmailAddressCommander.deactivate(vampireRecord).get should throwA[PrimaryEmailAddressException]
          }

          userEmailAddressRepo.getByAddressAndUser(userId, abeEmail).get tap { abeRecord =>
            userEmailAddressCommander.deactivate(abeRecord) should beASuccessfulTry
          }
          userEmailAddressRepo.getAllByUser(userId).map(_.address) === Seq(vampireEmail)

          userEmailAddressRepo.getByAddressAndUser(userId, vampireEmail).get tap { vampireRecord =>
            userEmailAddressCommander.deactivate(vampireRecord).get should throwA[LastEmailAddressException]
          }

          true === true

        }
      }
    }
  }
}
