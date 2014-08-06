package com.keepit.commanders

import com.google.inject.{ Injector, Inject }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, ElectronicMailRepo, EmailAddress }
import com.keepit.model._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class SendgridCommanderTest extends Specification with ShoeboxTestInjector {
  def setup(db: Database)(implicit injector: Injector) = {
    val emailAddrRepo = inject[UserEmailAddressRepo]
    val userRepo = inject[UserRepo]
    val emailRepo = inject[ElectronicMailRepo]

    db.readWrite { implicit rw =>
      val user = userRepo.save(User(firstName = "John", lastName = "Doe"))
      val emailAddr = emailAddrRepo.save(UserEmailAddress(address = EmailAddress("johndoe@gmail.com"), userId = user.id.get))
      val email = emailRepo.save(ElectronicMail(
        from = SystemEmailAddress.ENG,
        to = List(EmailAddress("johndoe@gmail.com")),
        subject = "Welcome",
        htmlBody = "Hi",
        category = NotificationCategory.User.WELCOME
      ))

      (user, emailAddr, email)
    }
  }

  def mockSendgridEvent(externalId: ExternalId[ElectronicMail]): SendgridEvent =
    SendgridEvent(
      event = Some(SendgridEventTypes.CLICK),
      mailId = Some(externalId),
      timestamp = DateTime.now,
      smtpId = None,
      reason = None,
      useragent = None,
      url = None,
      response = None,
      id = None,
      email = None
    )

  var modules = Seq(
    FakeShoeboxServiceModule()
  )

  "SendgridCommander" should {

    "processNewEvents" should {

      "bounce events unsubscribe the email from all notifications" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val optOutRepo = inject[EmailOptOutRepo]

          val (user, emailAddr, email) = setup(db)
          val sgEvent = mockSendgridEvent(email.externalId).copy(event = Some(SendgridEventTypes.BOUNCE))

          db.readOnlyMaster { implicit session =>
            optOutRepo.hasOptedOut(emailAddr.address) should beFalse
            commander.processNewEvents(Seq(sgEvent))
            optOutRepo.hasOptedOut(emailAddr.address) should beTrue
          }
        }
      }

      "bounce events unsubscribe the email from all notifications" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val optOutRepo = inject[EmailOptOutRepo]

          val (user, emailAddr, email) = setup(db)
          val sgEvent = mockSendgridEvent(email.externalId).copy(event = Some(SendgridEventTypes.UNSUBSCRIBE))

          db.readOnlyMaster { implicit session =>
            optOutRepo.hasOptedOut(emailAddr.address) should beFalse
            commander.processNewEvents(Seq(sgEvent))
            optOutRepo.hasOptedOut(emailAddr.address) should beTrue
          }
        }
      }

      "click events ignore confirmed emails" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val emailAddrRepo = inject[UserEmailAddressRepo]

          val (user, emailAddr, email) = setup(db)
          val sgEvent = mockSendgridEvent(email.externalId)

          val verifiedEmail = db.readWrite { implicit session =>
            emailAddrRepo.save(emailAddr.copy(state = UserEmailAddressStates.VERIFIED))
          }

          commander.processNewEvents(Seq(sgEvent))

          db.readOnlyMaster { implicit session =>
            val fetchedEmailAddr = emailAddrRepo.get(emailAddr.id.get)
            verifiedEmail.seq === fetchedEmailAddr.seq
            verifiedEmail.updatedAt === fetchedEmailAddr.updatedAt
          }
        }
      }

      "click events confirm unconfirmed emails" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val emailAddrRepo = inject[UserEmailAddressRepo]

          val (user, emailAddr, email) = setup(db)
          val sgEvent = mockSendgridEvent(email.externalId)

          commander.processNewEvents(Seq(sgEvent))

          db.readOnlyMaster { implicit session =>
            val fetchedEmailAddr = emailAddrRepo.get(emailAddr.id.get)
            fetchedEmailAddr.seq mustNotEqual emailAddr.seq
            fetchedEmailAddr.verified must beTrue
            fetchedEmailAddr.verifiedAt must beSome
            fetchedEmailAddr.state === UserEmailAddressStates.VERIFIED
          }
        }
      }
    }
  }
}
