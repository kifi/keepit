package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.template.{ EmailTip, EmailTrackingParam }
import com.keepit.common.mail.{ PostOffice, SystemEmailAddress, EmailAddress, FakeOutbox }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.SocialNetworks.FACEBOOK
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.Await

class EmailSenderTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
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

  "InviteToKifiSender" should {
    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[InviteToKifiSender]
        val toAddress = EmailAddress("taco@gmail.com")
        val inviteId = ExternalId[Invitation]()
        val fromUser = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"), username = Username("test"), normalizedUsername = "test")))
        }
        val email = Await.result(sender(toAddress, fromUser.id.get, inviteId), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.fromName === Some("Billy Madison (via Kifi)")
        email.from === SystemEmailAddress.INVITATION
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.NonUser.INVITATION)
        email.extraHeaders.get.apply(PostOffice.Headers.REPLY_TO) === fromUser.primaryEmail.get.address
      }
    }
  }

  "WelcomeEmailSender" should {
    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[WelcomeEmailSender]
        val toUser = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"), username = Username("test"), normalizedUsername = "test")))
        }
        val email = Await.result(sender.sendToUser(toUser.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.subject === "Let's get started with Kifi"
        val html = email.htmlBody.value
        html must contain("Hey Billy,")

        val trackingCode = EmailTrackingParam(
          subAction = Some("findMoreFriendsBtn"),
          tip = Some(EmailTip.ConnectFacebook)).encode

        html must contain("utm_source=fromKifi&utm_medium=email&utm_campaign=welcome&utm_content=findMoreFriendsBtn"
          + s"&${EmailTrackingParam.paramName}=$trackingCode")

        val text = email.textBody.get.value
        text must contain("Hey Billy,")
      }
    }
  }

  "FriendRequestMadeEmailSender" should {
    def testFriendConnectionMade(toUser: User, category: NotificationCategory, network: Option[SocialNetworkType] = None)(implicit injector: Injector) = {
      val outbox = inject[FakeOutbox]
      val sender = inject[FriendConnectionMadeEmailSender]
      val friendUser = db.readWrite { implicit rw =>
        inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"), username = Username("test"), normalizedUsername = "test")))
      }

      val email = Await.result(sender.sendToUser(toUser.id.get, friendUser.id.get, category, network), Duration(5, "seconds"))
      outbox.size === 1
      outbox(0) === email

      email.fromName === Some("Billy Madison (via Kifi)")
      email.to === Seq(EmailAddress("johnny@gmail.com"))
      email.category === NotificationCategory.toElectronicMailCategory(category)
      val html = email.htmlBody.value
      html must contain("Hi Johnny")

      email
    }

    "friend request accepted email" in {
      "sends email" in {
        withDb(modules: _*) { implicit injector =>
          val toUser = db.readWrite { implicit rw =>
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"), username = Username("test"), normalizedUsername = "test")))
          }

          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get, Seq.empty)

          val email = testFriendConnectionMade(toUser, NotificationCategory.User.FRIEND_ACCEPTED)
          val html = email.htmlBody.value
          email.subject === "Billy Madison accepted your Kifi friend request"
          html must contain("Billy Madison accepted your Kifi")
          html must contain("utm_campaign=friendRequestAccepted")

          val text = email.textBody.get.value
          text must contain("Billy Madison accepted your Kifi")
        }
      }
    }

    "connection made email for new user from Facebook/LinkedIn" in {
      Seq(
        (SocialNetworks.FACEBOOK, "Facebook friend"),
        (SocialNetworks.LINKEDIN, "LinkedIn connection")
      ).map {
          case (network, networkName) => withDb(modules: _*) { implicit injector =>
            val (toUser, friends) = db.readWrite { implicit rw =>
              (
                inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com")), username = Username("test"), normalizedUsername = "test")),
                inject[ShoeboxTestFactory].createUsers()
              )
            }

            val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
            abook.addFriendRecommendationsExpectations(toUser.id.get,
              Seq(friends._1, friends._2, friends._3, friends._4).map(_.id.get))

            val email = testFriendConnectionMade(toUser, NotificationCategory.User.SOCIAL_FRIEND_JOINED, Some(network))
            val html = email.htmlBody.value
            val text = email.textBody.get.value
            email.subject === s"Your $networkName Billy just joined Kifi"
            html must contain("utm_campaign=socialFriendJoined")
            html must contain(s"Your $networkName, Billy Madison, joined Kifi")
            html must contain(s"You and Billy are now")

            text must contain(s"Your $networkName, Billy Madison, joined Kifi")
            text must contain(s"You and Billy are now")
          }
        }
    }

    "connection made email for old user" in {

      "sends the email" in {
        withDb(modules: _*) { implicit injector =>
          val (toUser, friends) = db.readWrite { implicit rw =>
            (
              inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com")), username = Username("test"), normalizedUsername = "test")),
              inject[ShoeboxTestFactory].createUsers()
            )
          }

          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get,
            Seq(friends._1, friends._2, friends._3, friends._4).map(_.id.get))
          val email = testFriendConnectionMade(toUser, NotificationCategory.User.CONNECTION_MADE, Some(FACEBOOK))
          val html = email.htmlBody.value
          val text = email.textBody.get.value
          email.subject === "You are now friends with Billy Madison on Kifi!"
          html must contain("utm_campaign=connectionMade")
          html must contain("You have a new connection on Kifi")
          html must contain("Your Facebook friend, Billy Madison, is now connected to you on Kifi")

          text must contain("You have a new connection on Kifi")
          text must contain("Your Facebook friend, Billy Madison, is now connected to you on Kifi")
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
          val saveUser = inject[UserRepo].save _
          (
            saveUser(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("test"), normalizedUsername = "test")),
            saveUser(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"), username = Username("test"), normalizedUsername = "test")))
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
            inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("test"), normalizedUsername = "test")),
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com"), username = Username("test"), normalizedUsername = "test")))
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
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com"), username = Username("test"), normalizedUsername = "test")))
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

  "LibraryInviteEmailSender" should {
    implicit val config = PublicIdConfiguration("secret key")

    def setup()(implicit injector: Injector) = {
      val keepRepo = inject[KeepRepo]
      val urlRepo = inject[URLRepo]
      val uriRepo = inject[NormalizedURIRepo]

      db.readWrite { implicit rw =>
        val user1 = userRepo.save(User(firstName = "Tom", lastName = "Brady", username = Username("tom"), normalizedUsername = "b", primaryEmail = Some(EmailAddress("tombrady@gmail.com"), username = Username("test"), normalizedUsername = "test")))
        val user2 = userRepo.save(User(firstName = "Aaron", lastName = "Rodgers", username = Username("aaron"), normalizedUsername = "a", primaryEmail = Some(EmailAddress("aaronrodgers@gmail.com"), username = Username("test"), normalizedUsername = "test")))
        val lib1 = libraryRepo.save(Library(name = "Football", ownerId = user1.id.get, slug = LibrarySlug("football"),
          visibility = LibraryVisibility.PUBLISHED, memberCount = 1, description = Some("Lorem ipsum")))

        val uri = uriRepo.save(NormalizedURI(url = "http://www.kifi.com", urlHash = UrlHash("abc")))
        // todo(andrew) jared compiler bug if url_ var is named url
        val url_ = urlRepo.save(URL(url = "http://www.kifi.com", domain = None, normalizedUriId = uri.id.get))
        val keep = keepRepo.save(Keep(urlId = url_.id.get, url = url_.url, libraryId = lib1.id, uriId = uri.id.get, visibility = LibraryVisibility.SECRET, userId = user1.id.get, source = KeepSource.keeper, inDisjointLib = false))

        libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))

        val invite = LibraryInvite(libraryId = lib1.id.get, ownerId = user1.id.get, access = LibraryAccess.READ_ONLY, message = Some("check this out!"))

        (user1, user2, lib1, invite)
      }
    }

    def testHtml(html: String): MatchResult[_] = {
      html must contain("Football")
      html must contain("Tom invited you to")
      html must contain("Tom Brady")
      html must contain("Lorem ipsum")
      html must contain("TEST_MODE/tom/football")
      html must contain("check this out!")
    }

    "sends invite to user (userId)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteUser = invite.copy(userId = user2.id)
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]
        val email = Await.result(inviteSender.inviteUserToLibrary(inviteUser), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.subject === "Tom Brady invited you to follow Football!"
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value
        testHtml(html)
      }
    }

    "send invite to non-user (email)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteNonUser = invite.copy(emailAddress = Some(EmailAddress("aaronrodgers@gmail.com")))
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]
        val email = Await.result(inviteSender.inviteUserToLibrary(inviteNonUser), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.subject === "Tom Brady invited you to follow Football!"
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value
        testHtml(html)
        html must contain(invite.passPhrase)
      }
    }
  }

}
