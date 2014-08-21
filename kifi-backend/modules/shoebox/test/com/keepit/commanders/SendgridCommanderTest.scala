package com.keepit.commanders

import com.google.inject.{ Injector }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, ElectronicMailRepo, EmailAddress }
import com.keepit.common.store.{ FakeShoeboxStoreModule, FakeS3ImageStore }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.{ FakeCuratorServiceClientImpl, CuratorServiceClient, FakeCuratorServiceClientModule }
import com.keepit.model._
import com.keepit.scraper.FakeScraperServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import views.html.admin.user

class SendgridCommanderTest extends Specification with ShoeboxTestInjector {
  def setup(db: Database)(implicit injector: Injector): (User, UserEmailAddress, ElectronicMail) = {
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
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeExternalServiceModule(),
    FakeShoeboxStoreModule()
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

      "click events for digest emails" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val emailAddrRepo = inject[UserEmailAddressRepo]
          val emailRepo = inject[ElectronicMailRepo]
          val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]

          val (user: User, emailAddr: UserEmailAddress, email: ElectronicMail) = {
            val tuple = setup(db)
            val updatedEmail = db.readWrite { implicit rw =>
              emailRepo.save(tuple._3.copy(category = NotificationCategory.User.DIGEST, senderUserId = tuple._1.id))
            }
            tuple.copy(_3 = updatedEmail)
          }

          val uriRepo = inject[NormalizedURIRepo]
          val uri = db.readWrite { implicit rw =>
            uriRepo.save(NormalizedURI(url = "https://www.kifi.com", urlHash = UrlHash("https://www.kifi.com")))
          }
          val sgEvent = mockSendgridEvent(email.externalId).copy(url = Some("https://www.kifi.com"))

          commander.processNewEvents(Seq(sgEvent))

          val (actualUserId, actualUriId, actualFeedback) = curator.updatedUriRecommendationFeedback.head
          actualUriId === uri.id.get
          actualUserId === user.id.get
          actualFeedback === UriRecommendationFeedback(clicked = Some(true), kept = None)
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
