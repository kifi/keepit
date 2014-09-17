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

        val text = email.textBody.get.value
        text must contain("Hey Billy,")
      }
    }
  }

  "FriendRequestMadeEmailSender" should {
    def testFriendConnectionMade(toUser: User, category: NotificationCategory)(implicit injector: Injector) = {
      val outbox = inject[FakeOutbox]
      val sender = inject[FriendConnectionMadeEmailSender]
      val friendUser = db.readWrite { implicit rw =>
        inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"))))
      }

      val email = Await.result(sender.sendToUser(toUser.id.get, friendUser.id.get, category), Duration(5, "seconds"))
      outbox.size === 1
      outbox(0) === email

      email.fromName === Some("Billy Madison (via Kifi)")
      email.to === Seq(EmailAddress("johnny@gmail.com"))
      email.category === NotificationCategory.toElectronicMailCategory(category)
      val html = email.htmlBody.value
      html must contain("Hey Johnny")

      email
    }

    "friend request accepted email" in {
      "sends email without PYMK tip" in {
        withDb(modules: _*) { implicit injector =>
          val toUser = db.readWrite { implicit rw =>
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"))))
          }

          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get, Seq.empty)

          val email = testFriendConnectionMade(toUser, NotificationCategory.User.FRIEND_ACCEPTED)
          val html = email.htmlBody.value
          email.subject === "Billy Madison accepted your Kifi friend request"
          html must contain("Billy accepted your Kifi")
          html must contain("You and Billy Madison are now")
          html must contain("utm_campaign=friendRequestAccepted")

          // weak PYMK tests
          html must not contain "Find friends on Kifi to benefit from their keeps"
          html must not contain "Aaron"
          html must not contain "Bryan"
          html must not contain "Anna"
          html must not contain "Dean"

          val text = email.textBody.get.value
          text must contain("Billy accepted your Kifi")
          text must contain("You and Billy Madison are now")
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

          val email = testFriendConnectionMade(toUser, NotificationCategory.User.FRIEND_ACCEPTED)
          val html = email.htmlBody.value

          // weak PYMK tests (just make sure it's there)
          html must contain("Aaron")
          html must contain("Bryan")
          html must contain("Anna")
          html must contain("Dean")

          val text = email.textBody.get.value
          text must contain("Billy accepted your Kifi")
          text must contain("You and Billy Madison are now")
        }
      }
    }

    "connection made email" in {

      "sends with PYMK" in {
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
          val email = testFriendConnectionMade(toUser, NotificationCategory.User.CONNECTION_MADE)
          val html = email.htmlBody.value
          val text = email.textBody.get.value
          email.subject === "You are now friends with Billy Madison on Kifi!"
          html must contain("utm_campaign=connectionMade")
          html must contain("You and Billy Madison are now")
          html must contain("now friends with Billy Madison on Kifi. Enjoy Billy’s")
          html must contain("message Billy directly")

          // weak PYMK tests (just make sure it's there)
          html must contain("Aaron")
          html must contain("Bryan")
          html must contain("Anna")
          html must contain("Dean")

          text must contain("You and Billy Madison are now")
          text must contain("now friends with Billy Madison on Kifi. Enjoy Billy's")
          text must contain("message Billy directly")
        }
      }
    }
  }

  "FriendRequestEmailSender" should {
    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[FriendRequestEmailSender]
        val (toUser, fromUser) = db.readWrite { implicit rw =>
          (
            inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")))),
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"))))
          )
        }
        val email = Await.result(sender.sendToUser(toUser.id.get, fromUser.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.subject === "Johnny Manziel sent you a friend request."
        email.fromName === Some(s"Johnny Manziel (via Kifi)")
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.FRIEND_REQUEST)
        val html = email.htmlBody.value
        html must contain("Hi Billy")
        html must contain("Johnny Manziel wants to be your kifi friend")
        html must contain("utm_campaign=friendRequest")

        val text = email.textBody.get.value
        text must contain("Hi Billy")
        text must contain("Johnny Manziel wants to be your kifi friend")
        text must contain("Add Johnny by visiting the link below")
      }
    }
  }

  "ContactJoinedEmailSender" should {

    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[ContactJoinedEmailSender]
        val (toUser, fromUser) = db.readWrite { implicit rw =>
          (
            inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")))),
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"))))
          )
        }
        val email = Await.result(sender.sendToUser(toUser.id.get, fromUser.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.subject === s"Johnny Manziel joined Kifi. Want to connect?"
        email.fromName === Some(s"Johnny Manziel (via Kifi)")
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.CONTACT_JOINED)
        val html = email.htmlBody.value
        html must contain("Hi Billy,")
        html must contain("Johnny Manziel just joined kifi.")
        html must contain("utm_campaign=contactJoined")
        html must contain("friend=" + fromUser.externalId)

        val text = email.textBody.get.value
        text must contain("Hi Billy,")
        text must contain("Johnny Manziel just joined kifi.")
        text must contain("to add Johnny as a friend")
        text must contain("friend=" + fromUser.externalId)
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

        val text = email.textBody.get.value
        text must contain("Hi Billy,")
        text must contain(s"TEST_MODE/password/$token")
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

        val text = email.textBody.get.value
        text must contain("Yay, you are on the kifi ANDROID wait list!")
      }
    }
  }

}
