package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.template.{ EmailTip, EmailTrackingParam }
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, ElectronicMailRepo, EmailAddress }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time.zones
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.scraper.FakeScraperServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.common.concurrent.WatchableExecutionContext

class SendgridCommanderTest extends Specification with ShoeboxTestInjector {
  def setup(db: Database, category: NotificationCategory = NotificationCategory.User.WELCOME)(implicit injector: Injector): (User, UserEmailAddress, ElectronicMail) = {
    val emailAddrRepo = inject[UserEmailAddressRepo]
    val userRepo = inject[UserRepo]
    val emailRepo = inject[ElectronicMailRepo]

    db.readWrite { implicit rw =>
      val user = userRepo.save(User(firstName = "John", lastName = "Doe", username = Username("test"), normalizedUsername = "test"))
      val emailAddr = emailAddrRepo.save(UserEmailAddress(address = EmailAddress("johndoe@gmail.com"), userId = user.id.get))
      val email = emailRepo.save(ElectronicMail(
        from = SystemEmailAddress.ENG,
        to = List(EmailAddress("johndoe@gmail.com")),
        subject = "Welcome",
        htmlBody = "Hi",
        category = category
      ))

      (user, emailAddr, email)
    }
  }

  def mockSendgridEvent(externalId: ExternalId[ElectronicMail], email: Option[ElectronicMail] = None): SendgridEvent =
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
      email = email.map(_.to.head.address)
    )

  var modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "SendgridCommander" should {

    "processNewEvents" should {

      "sends user event to heimdal with arbitrary context properties in the URL" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
          val (user, emailAddr, email) = setup(db)

          val someDate = new DateTime(2014, 6, 14, 13, 14, zones.UTC)
          val trackingParam = EmailTrackingParam(
            subAction = Some("kifiLogo"),
            variableComponents = Seq("1Friend"),
            tip = Some(EmailTip.FriendRecommendations),
            auxiliaryData = {
              val ctxBuilder = new HeimdalContextBuilder
              ctxBuilder += ("version", 1)
              ctxBuilder += ("tipLocation", "top")
              ctxBuilder += ("isAdmin", true)
              ctxBuilder += ("someDate", someDate)
              Some(ctxBuilder.build)
            }
          )

          val sgEvent = mockSendgridEvent(email.externalId, Some(email)).copy(
            url = Some(s"http://www.kifi.com/?${EmailTrackingParam.paramName}=${EmailTrackingParam.encode(trackingParam)}"),
            event = Some(SendgridEventTypes.CLICK))

          commander.processNewEvents(Seq(sgEvent))
          inject[WatchableExecutionContext].drain()

          heimdal.eventsRecorded === 1

          val actualEvent = heimdal.trackedEvents(0)
          actualEvent.eventType === UserEventTypes.WAS_NOTIFIED
          actualEvent.context.get[String]("subaction").get === "kifiLogo"
          actualEvent.context.getSeq[String]("emailComponents").get === Seq("1Friend")
          actualEvent.context.get[EmailTip]("emailTip").get === EmailTip.FriendRecommendations
          actualEvent.context.get[Double]("version").get === 1.0
          actualEvent.context.get[String]("tipLocation").get === "top"
          actualEvent.context.get[Boolean]("isAdmin").get === true
          actualEvent.context.get[DateTime]("someDate").get === someDate
        }
      }

      "sends user_user_kifi for digest email clicks" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[SendgridCommander]
          val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
          val (user, emailAddr, email) = setup(db, NotificationCategory.User.DIGEST)

          val trackingParam = EmailTrackingParam(subAction = Some("clickedArticleTitle"))

          val sgEvent = mockSendgridEvent(email.externalId, Some(email)).copy(
            url = Some(s"http://www.kifi.com/?foo=bar&amp;baz=moo&${EmailTrackingParam.paramName}=${EmailTrackingParam.encode(trackingParam)}"),
            event = Some(SendgridEventTypes.CLICK))

          commander.processNewEvents(Seq(sgEvent))
          inject[WatchableExecutionContext].drain()

          heimdal.trackedEvents.size === 2
          heimdal.trackedEvents(0).eventType === UserEventTypes.WAS_NOTIFIED

          val actualEvent = heimdal.trackedEvents(1)
          actualEvent.eventType === UserEventTypes.USED_KIFI
          actualEvent.context.get[String]("action").get === "clickedArticleTitle"
          actualEvent.context.get[String]("subsource").get === "digest"
        }
      }

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
