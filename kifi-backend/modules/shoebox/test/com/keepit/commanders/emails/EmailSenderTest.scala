package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.{ EmailAddress, FakeOutbox }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NotificationCategory, User, UserRepo, PasswordResetRepo }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.Await

class EmailSenderTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeScrapeSchedulerModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule())

  "WelcomeEmailSender" should {
    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[WelcomeEmailSender]
        val toUser = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"))))
        }
        val email = Await.result(sender.sendToUser(toUser.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.subject === "Let's get started with Kifi"
        val html = email.htmlBody.value
        html must contain("Hey Billy,")
        html must contain("utm_source=confirmEmail&utm_medium=email&utm_campaign=welcomeEmail")
      }
    }
  }

  "FriendRequestAcceptedEmailSender" should {
    def testFriendRequestAcceptedEmail(toUser: User)(implicit injector: Injector) = {
      val outbox = inject[FakeOutbox]
      val sender = inject[FriendRequestAcceptedEmailSender]
      val friendUser = db.readWrite { implicit rw =>
        inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"))))
      }

      val email = Await.result(sender.sendToUser(toUser.id.get, friendUser.id.get), Duration(5, "seconds"))
      outbox.size === 1
      outbox(0) === email

      email.fromName === Some("Billy Madison (via Kifi)")
      email.to === Seq(EmailAddress("johnny@gmail.com"))
      email.subject === "Billy Madison accepted your Kifi friend request"
      email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.FRIEND_ACCEPTED)
      val html = email.htmlBody.value
      html must contain("Hey Johnny")
      html must contain("Billy accepted your Kifi")
      html must contain("You and Billy Madison are now")
      html must contain("utm_campaign=friendRequestAccepted")
      html
    }

    "sends email without PYMK tip" in {
      withDb(modules: _*) { implicit injector =>
        val toUser = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"))))
        }

        val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abook.addFriendRecommendationsExpectations(toUser.id.get, Seq.empty)

        val html = testFriendRequestAcceptedEmail(toUser)

        // weak PYMK tests
        html must not contain "Aaron"
        html must not contain "Bryan"
        html must not contain "Anna"
        html must not contain "Dean"
      }
    }

    "sends email with PYMK tip" in {
      withDb(modules: _*) { implicit injector =>
        val (toUser, friends) = db.readWrite { implicit rw =>
          (
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com")))),
            inject[ShoeboxTestFactory].createUsers()
          )
        }

        val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abook.addFriendRecommendationsExpectations(toUser.id.get,
          Seq(friends._1, friends._2, friends._3, friends._4).map(_.id.get))

        val html = testFriendRequestAcceptedEmail(toUser)

        // weak PYMK tests (just make sure it's there)
        html must contain("Aaron")
        html must contain("Bryan")
        html must contain("Anna")
        html must contain("Dean")
      }
    }
  }


  "ResetPasswordEmailSender" should {

    "sends reset password email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val passwordResetRepo = inject[PasswordResetRepo]
        val resetSender = inject[ResetPasswordEmailSender]
        val user = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"))))
        }
        val email = Await.result(resetSender.sendToUser(user.id.get, user.primaryEmail.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        val tokens = db.readOnlyMaster { implicit s => passwordResetRepo.getByUser(user.id.get) }
        tokens.size === 1
        val token = tokens(0).token
        token.size === 8

        val html = email.htmlBody.value
        html must contain("Hi Billy,")
        html must contain(s"TEST_MODE/password/$token")
        html must contain("utm_campaign=passwordReset")
      }
    }

  }

  "FeatureWaitlistEmailSender" should {

    "sends correct email for mobile_app" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[FeatureWaitlistEmailSender]
        val toEmail = EmailAddress("foo@bar.com")
        val email = Await.result(sender.sendToUser(toEmail, "mobile_app"), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(toEmail)
        email.subject === "You're on the wait list"
        val html = email.htmlBody.value
        html must contain("Yay, you are on the kifi ANDROID wait list!")
        html must contain("utm_campaign=mobile_app_waitlist")
      }
    }
  }

}
