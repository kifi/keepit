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
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._

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
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("test"), normalizedUsername = "test"))
        }
        val email = Await.result(sender(toAddress, fromUser.id.get, inviteId), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.fromName === Some("Billy Madison (via Kifi)")
        email.from === SystemEmailAddress.INVITATION
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.NonUser.INVITATION)
        email.extraHeaders.get.apply(PostOffice.Headers.REPLY_TO) === fromUser.primaryEmail.get.address

        val params = List("utm_campaign=na", "utm_source=kifi_invite", "utm_medium=vf_email", "kcid=na-vf_email-kifi_invite")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true)
      }
    }
  }

  "WelcomeEmailSender" should {
    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[WelcomeEmailSender]
        val toUser = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("test"), normalizedUsername = "test"))
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

        html must contain("utm_source=fromKifi&amp;utm_medium=email&amp;utm_campaign=welcome&amp;utm_content=findMoreFriendsBtn&amp;kcid=welcome-email-fromKifi"
          + s"&amp;${EmailTrackingParam.paramName}=$trackingCode")

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
        inject[UserRepo].save(
          User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")),
            username = Username("billy"), normalizedUsername = "billy"))
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
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com")), username = Username("test"), normalizedUsername = "test"))
          }

          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get, Seq.empty)

          val email = testFriendConnectionMade(toUser, NotificationCategory.User.FRIEND_ACCEPTED)
          email.from === SystemEmailAddress.NOTIFICATIONS
          email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === SystemEmailAddress.SUPPORT.address

          val html = email.htmlBody.value
          email.subject === "Billy Madison accepted your invitation to connect"
          html must contain("""<a href="http://dev.ezkeep.com:9000/billy?""")
          html must contain("""Billy Madison</a> accepted your invitation to connect""")
          html must contain("""<a href="http://dev.ezkeep.com:9000/billy?utm_source=fromFriends&amp;utm_medium=email&amp;utm_campaign=friendRequestAccepted&amp;utm_content=friendConnectionMade&amp;kcid=friendRequestAccepted-email-fromFriends&amp;dat=eyJsIjoiZnJpZW5kQ29ubmVjdGlvbk1hZGUiLCJjIjpbXSwidCI6W119&amp;kma=1"><img src="https://cloudfront/users/2/pics/100/0.jpg" alt="Billy Madison" width="73" height="73" style="display:block;" border="0"/></a>""")

          val text = email.textBody.get.value
          text must contain("""Billy Madison accepted your invitation to connect""")
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
            html must contain(s"""Your $networkName, <a href="http://dev.ezkeep.com:9000/billy""")
            html must contain("You and <a href=\"http://dev.ezkeep.com:9000/billy?")
            html must contain("Billy Madison</a> are now connected on Kifi")

            text must contain(s"Billy Madison, joined Kifi")
            text must contain("Billy Madison are now connected on Kifi")
          }
        }
    }

    "connection made email for old user" in {

      "sends the email" in {
        withDb(modules: _*) { implicit injector =>
          val (toUser, friends) = db.readWrite { implicit rw =>
            (
              user().withName("Johnny", "Manziel").withEmailAddress("johnny@gmail.com").withUsername("johnny").saved,
              inject[ShoeboxTestFactory].createUsers()
            )
          }
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get,
            Seq(friends._1, friends._2, friends._3, friends._4).map(_.id.get))
          val email = testFriendConnectionMade(toUser, NotificationCategory.User.CONNECTION_MADE, Some(FACEBOOK))
          val html = email.htmlBody.value
          val text = email.textBody.get.value
          email.subject === "You and Billy Madison are now connected on Kifi!"
          html must contain("utm_campaign=connectionMade")
          html must contain("You have a new connection on Kifi")
          html must contain("""Your Facebook friend, <a href="http://dev.ezkeep.com:9000/billy?""")

          text must contain("You have a new connection on Kifi")
          text must contain("""Your Facebook friend, Billy""")
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
            saveUser(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("billy"), normalizedUsername = "billy")),
            saveUser(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com")), username = Username("johnny"), normalizedUsername = "johnny"))
          )
        }
        val email = Await.result(sender.sendToUser(toUser.id.get, fromUser.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.subject === "Johnny Manziel wants to connect with you on Kifi"
        email.fromName === Some(s"Johnny Manziel (via Kifi)")
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.FRIEND_REQUEST)
        val html = email.htmlBody.value
        html must contain("Hi Billy")
        html must contain("Johnny Manziel wants to connect with you on Kifi.")
        html must contain("utm_campaign=friendRequest")

        val text = email.textBody.get.value
        text must contain("Hi Billy")
        text must contain("Johnny Manziel wants to connect with you on Kifi.")
        text must contain("You can visit this link to accept the invitation")
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
            inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("billy"), normalizedUsername = "billy")),
            inject[UserRepo].save(User(firstName = "Johnny", lastName = "Manziel", primaryEmail = Some(EmailAddress("johnny@gmail.com")), username = Username("johnny"), normalizedUsername = "johnny"))
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
        html must contain("Johnny Manziel just joined Kifi.")
        html must contain("utm_campaign=contactJoined")
        html must contain(s"/${fromUser.username.value}?intent=connect")

        val text = email.textBody.get.value
        text must contain("Hi Billy,")
        text must contain("Johnny Manziel just joined Kifi.")
        text must contain("to invite Johnny to connect on Kifi")
        text must contain(s"/${fromUser.username.value}?intent=connect")
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
          inject[UserRepo].save(User(firstName = "Billy", lastName = "Madison", primaryEmail = Some(EmailAddress("billy@gmail.com")), username = Username("billy"), normalizedUsername = "billy"))
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
        html must contain(s"http://dev.ezkeep.com:9000/password/$token")
        html must contain("utm_campaign=passwordReset")

        val text = email.textBody.get.value
        text must contain("Hi Billy,")
        text must contain(s"http://dev.ezkeep.com:9000/password/$token")
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
        val user1 = userRepo.save(User(firstName = "Tom", lastName = "Brady", username = Username("tom"), normalizedUsername = "b", primaryEmail = Some(EmailAddress("tombrady@gmail.com"))))
        val user2 = userRepo.save(User(firstName = "Aaron", lastName = "Rodgers", username = Username("aaron"), normalizedUsername = "a", primaryEmail = Some(EmailAddress("aaronrodgers@gmail.com"))))
        val lib1 = libraryRepo.save(Library(name = "Football", ownerId = user1.id.get, slug = LibrarySlug("football"),
          visibility = LibraryVisibility.SECRET, memberCount = 1, description = Some("Lorem ipsum")))

        val uri = uriRepo.save(NormalizedURI(url = "http://www.kifi.com", urlHash = UrlHash("abc")))
        // todo(andrew) jared compiler bug if url_ var is named url
        val url_ = urlRepo.save(URL(url = "http://www.kifi.com", domain = None, normalizedUriId = uri.id.get))
        val keep = keepRepo.save(Keep(urlId = url_.id.get, url = url_.url, libraryId = lib1.id, uriId = uri.id.get, visibility = LibraryVisibility.SECRET, userId = user1.id.get, source = KeepSource.keeper, inDisjointLib = false))

        libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

        val invite = LibraryInvite(libraryId = lib1.id.get, inviterId = user1.id.get, access = LibraryAccess.READ_ONLY, message = Some("check this out!"), authToken = "abcdefg")

        (user1, user2, lib1, invite)
      }
    }

    def testHtml(html: String): MatchResult[_] = {
      html must contain("Football")
      html must contain("Tom invited you to")
      html must contain("Tom Brady")
      html must contain("Lorem ipsum")
      html must contain("http://dev.ezkeep.com:9000/tom/football")
      html must contain("check this out!")
      html must contain("authToken=abcdefg")
    }

    "sends invite to user (userId)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteUser = invite.copy(userId = user2.id)
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]
        val email = Await.result(inviteSender.sendInvite(inviteUser), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.LIBRARY_INVITATION)
        email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === "tombrady@gmail.com"
        email.subject === "Tom Brady invited you to follow Football!"
        email.htmlBody.contains("http://dev.ezkeep.com:9000/tom/football?") === true
        email.htmlBody.contains("<span style=\"color:#999999\">Tom Brady</span>") === true
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kcid=na-vf_email-library_invite", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true, true)
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value
        testHtml(html)
        html must not contain invite.passPhrase
      }
    }

    "send invite to non-user (email)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteNonUser = invite.copy(emailAddress = Some(EmailAddress("aaronrodgers@gmail.com")))
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]
        val email = Await.result(inviteSender.sendInvite(inviteNonUser), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.NonUser.LIBRARY_INVITATION)
        email.subject === "Tom Brady invited you to follow Football!"
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true)
        val html = email.htmlBody.value
        testHtml(html)
        html must contain(invite.passPhrase)

        db.readWrite { implicit session => libraryRepo.save(lib1.copy(visibility = LibraryVisibility.PUBLISHED)) }
        val emailWithoutPassPhrase = Await.result(inviteSender.sendInvite(inviteNonUser), Duration(5, "seconds")).get
        emailWithoutPassPhrase.subject === "Tom Brady invited you to follow Football!"
        emailWithoutPassPhrase.to(0) === EmailAddress("aaronrodgers@gmail.com")
        params.map(emailWithoutPassPhrase.htmlBody.contains(_)) === List(true, true, true, true)
        val htmlWithoutPassPhrase = emailWithoutPassPhrase.htmlBody.value
        testHtml(htmlWithoutPassPhrase)
        htmlWithoutPassPhrase must not contain invite.passPhrase
      }
    }
  }

  "TwitterWaitlistEmailSender" should {

    "sends confirmation email to user (userId)" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[TwitterWaitlistEmailSender]
        val toEmail = EmailAddress("foo@bar.com")
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Rocky", lastName = "Balboa", username = Username("tester"), normalizedUsername = "tester", primaryEmail = Some(toEmail)))
        }
        val email = Await.result(sender.sendToUser(toEmail, user.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(toEmail)
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.WAITLIST)
        email.subject === "You are on the list"
        val html = email.htmlBody.value
        html must contain("Hey Rocky")
        html must contain("Kifi Twitter library is ready")

        val text = email.textBody.get.value
        text must contain("Kifi Twitter library is ready")
      }
    }
  }

}
