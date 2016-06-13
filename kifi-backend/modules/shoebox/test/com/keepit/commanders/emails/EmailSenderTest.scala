package com.keepit.commanders.emails

import java.net.URLEncoder

import com.google.inject.Injector
import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.commanders.UserEmailAddressCommander
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.template.{ EmailTrackingParam }
import com.keepit.common.mail.{ PostOffice, SystemEmailAddress, EmailAddress, FakeOutbox }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.SocialNetworks.FACEBOOK
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.Await
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactory._

class EmailSenderTest extends Specification with ShoeboxTestInjector {
  implicit def publicIdConfiguration(implicit injector: Injector) = inject[PublicIdConfiguration]
  val modules = Seq(
    FakeExecutionContextModule(),
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
          UserFactory.user().withName("Billy", "Madison").withUsername("test").saved
        }
        val email = Await.result(sender(toAddress, fromUser.id.get, inviteId), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.fromName === Some("Billy Madison (via Kifi)")
        email.from === SystemEmailAddress.INVITATION
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.NonUser.INVITATION)
        email.extraHeaders === None

        val params = List("utm_campaign=na", "utm_source=kifi_invite", "utm_medium=vf_email", "kcid=na-vf_email-kifi_invite")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true)
      }
    }
  }

  "WelcomeEmailSender" should {
    "sends html-rich email" in {
      val WELCOME_EMAIL_SUBJECT = "Let's get started with Kifi"
      def WELCOME_SALUTATION(firstName: String) = "Hey " + firstName + ","
      val WELCOME_SENDER = "Eishay Smith"
      val WELCOME_SENDER_EMAIL = SystemEmailAddress.EISHAY_PUBLIC

      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[WelcomeEmailSender]
        val toUser = db.readWrite { implicit rw =>
          val toUser = UserFactory.user().withName("Billy", "Madison").withUsername("test").saved
          userEmailAddressCommander.intern(toUser.id.get, EmailAddress("billy@gmail.com")).get
          toUser
        }

        val email = Await.result(sender.sendToUser(userId = toUser.id.get, toAddress = None, verificationCode = None, installs = Set.empty), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.from === WELCOME_SENDER_EMAIL
        email.subject === WELCOME_EMAIL_SUBJECT
        val html = email.htmlBody.value
        html must contain(WELCOME_SALUTATION(toUser.firstName))

        html must contain("https://play.google.com/store/apps/details")

        val text = email.textBody.get.value
        text must contain("Hey Billy,")

        val scalaWords = Seq("homeUrl", "installExtUrl", "firstName", "iOsAppStoreUrl", "googlePlayStoreUrl", "howKifiWorksUrl", "eishayKifiUrl")
        scalaWords foreach { word => html must not contain word }
        scalaWords foreach { word => text must not contain word }
        1 === 1 // can't compile test without an explicit assertion at the end
      }
    }

    "sends html-plain email" in {
      val WELCOME_EMAIL_SUBJECT = "Let's get started with Kifi"
      def WELCOME_SALUTATION(firstName: String) = "Hey " + firstName + ","
      val WELCOME_SENDER = "Eishay Smith"
      val WELCOME_SENDER_EMAIL = SystemEmailAddress.EISHAY_PUBLIC

      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[WelcomeEmailSender]
        val toUser = db.readWrite { implicit rw =>
          val toUser = UserFactory.user().withName("Billy", "Madison").withUsername("test").saved
          userEmailAddressCommander.intern(toUser.id.get, EmailAddress("billy@gmail.com")).get
          toUser
        }

        val email = Await.result(sender.sendToUser(userId = toUser.id.get, toAddress = None, verificationCode = None, installs = Set.empty), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.from === WELCOME_SENDER_EMAIL
        email.subject === WELCOME_EMAIL_SUBJECT
        val html = email.htmlBody.value
        html must contain(WELCOME_SALUTATION(toUser.firstName))

        val text = email.textBody.get.value
        text must not contain ("firstName")
        text must contain("Hey Billy,")

        val scalaWords = Seq("homeUrl", "installExtUrl", "firstName", "iOsAppStoreUrl", "googlePlayStoreUrl", "howKifiWorksUrl", "eishayKifiUrl")
        scalaWords foreach { word => html must not contain word }
        scalaWords foreach { word => text must not contain word }

        1 === 1 // can't compile test without an explicit assertion at the end
      }
    }
  }

  "FriendRequestMadeEmailSender" should {
    def testFriendConnectionMade(toUser: User, category: NotificationCategory, network: Option[SocialNetworkType] = None)(implicit injector: Injector) = {
      val outbox = inject[FakeOutbox]
      val sender = inject[FriendConnectionMadeEmailSender]
      val friendUser = db.readWrite { implicit rw =>
        val friendUser = UserFactory.user().withName("Billy", "Madison").withUsername("billy").saved
        userEmailAddressCommander.intern(friendUser.id.get, EmailAddress("billy@gmail.com")).get
        friendUser
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
            val toUser = UserFactory.user().withName("Johnny", "Manziel").withUsername("test").saved
            userEmailAddressCommander.intern(toUser.id.get, EmailAddress("johnny@gmail.com")).get
            toUser
          }

          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get, Seq.empty)

          val email = testFriendConnectionMade(toUser, NotificationCategory.User.FRIEND_ACCEPTED)
          email.from === SystemEmailAddress.NOTIFICATIONS
          email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === SystemEmailAddress.SUPPORT.address

          val externalId = db.readOnlyMaster { implicit session => userRepo.getByUsername(Username("billy")).get.externalId }
          val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"us","uid":"$externalId"}""", "ascii")
          val html = email.htmlBody.value
          email.subject === "Billy Madison accepted your invitation to connect"
          html must contain(deepLink)
          html must contain("""Billy Madison</a> accepted your invitation to connect""")
          html must contain(s"""$deepLink&utm_source=fromFriends&amp;utm_medium=email&amp;utm_campaign=friendRequestAccepted&amp;utm_content=friendConnectionMade&amp;kcid=friendRequestAccepted-email-fromFriends&amp;dat=eyJsIjoiZnJpZW5kQ29ubmVjdGlvbk1hZGUiLCJjIjpbXSwidCI6W119&amp;kma=1"><img src="https://cloudfront/users/2/pics/100/0.jpg" alt="Billy Madison" width="73" height="73" style="display:block;" border="0"/></a>""")

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
              val toUser = UserFactory.user().withName("Johnny", "Manziel").withUsername("test").saved
              userEmailAddressCommander.intern(toUser.id.get, EmailAddress("johnny@gmail.com")).get
              val friends = inject[ShoeboxTestFactory].createUsers()
              (toUser, friends)
            }

            val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
            abook.addFriendRecommendationsExpectations(toUser.id.get,
              Seq(friends._1, friends._2, friends._3, friends._4).map(_.id.get))

            val email = testFriendConnectionMade(toUser, NotificationCategory.User.SOCIAL_FRIEND_JOINED, Some(network))
            val externalId = db.readOnlyMaster { implicit session => userRepo.getByUsername(Username("billy")).get.externalId }
            val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"us","uid":"$externalId"}""", "ascii")
            val html = email.htmlBody.value
            val text = email.textBody.get.value
            email.subject === s"Your $networkName Billy just joined Kifi"
            html must contain("utm_campaign=socialFriendJoined")
            html must contain(s"""Your $networkName, <a href="$deepLink""")
            html must contain(s"""You and <a href="$deepLink""")
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
            val toUser = UserFactory.user().withName("Johnny", "Manziel").withUsername("test").saved
            userEmailAddressCommander.intern(toUser.id.get, EmailAddress("johnny@gmail.com")).get
            val friends = inject[ShoeboxTestFactory].createUsers()
            (toUser, friends)
          }
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(toUser.id.get,
            Seq(friends._1, friends._2, friends._3, friends._4).map(_.id.get))
          val email = testFriendConnectionMade(toUser, NotificationCategory.User.CONNECTION_MADE, Some(FACEBOOK))
          val externalId = db.readOnlyMaster { implicit session => userRepo.getByUsername(Username("billy")).get.externalId }

          val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"us","uid":"$externalId"}""", "ascii")

          val html = email.htmlBody.value
          val text = email.textBody.get.value
          email.subject === "You and Billy Madison are now connected on Kifi!"
          html must contain("utm_campaign=connectionMade")
          html must contain("You have a new connection on Kifi")
          html must contain(s"""Your Facebook friend, <a href="$deepLink""")

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
          val toUser = UserFactory.user().withName("Billy", "Madison").withUsername("billy").saved
          userEmailAddressCommander.intern(toUser.id.get, EmailAddress("billy@gmail.com")).get
          val fromUser = UserFactory.user().withName("Johnny", "Manziel").withUsername("johnny").saved
          userEmailAddressCommander.intern(fromUser.id.get, EmailAddress("johnny@gmail.com")).get
          (toUser, fromUser)
        }
        val email = Await.result(sender.sendToUser(toUser.id.get, fromUser.id.get), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        val friendRequestLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode("""{"t":"fr"}""", "ascii")
        val friendProfileLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"us","uid":"${fromUser.externalId}""", "ascii")

        email.to === Seq(EmailAddress("billy@gmail.com"))
        email.subject === "Johnny Manziel wants to connect with you on Kifi"
        email.fromName === Some(s"Johnny Manziel (via Kifi)")
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.FRIEND_REQUEST)
        val html = email.htmlBody.value
        html must contain("Hi Billy")
        html must contain("Johnny Manziel wants to connect with you on Kifi.")
        html must contain("utm_campaign=friendRequest")
        html must contain(friendRequestLink)
        html must contain(friendProfileLink)

        val text = email.textBody.get.value
        text must contain("Hi Billy")
        text must contain("Johnny Manziel wants to connect with you on Kifi.")
        text must contain("You can visit this link to accept the invitation")
        text must contain("http://dev.ezkeep.com:9000/friends/requests")
      }
    }
  }

  "ContactJoinedEmailSender" should {

    "sends email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[ContactJoinedEmailSender]
        val (toUser, fromUser) = db.readWrite { implicit rw =>
          val toUser = UserFactory.user().withName("Billy", "Madison").withUsername("billy").saved
          userEmailAddressCommander.intern(toUser.id.get, EmailAddress("billy@gmail.com")).get
          val fromUser = UserFactory.user().withName("Johnny", "Manziel").withUsername("johnny").saved
          userEmailAddressCommander.intern(fromUser.id.get, EmailAddress("johnny@gmail.com")).get
          (toUser, fromUser)
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

        val fromDeepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"us","uid":"${fromUser.externalId}"}""", "ascii")
        html must contain(fromDeepLink)

        val fromUrl = s"http://dev.ezkeep.com:9000/${fromUser.username.value}"
        val text = email.textBody.get.value
        text must contain("Hi Billy,")
        text must contain("Johnny Manziel just joined Kifi.")
        text must contain("to invite Johnny to connect on Kifi")
        text must contain(fromUrl)
      }
    }
  }

  "ResetPasswordEmailSender" should {

    "sends reset password email" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val passwordResetRepo = inject[PasswordResetRepo]
        val resetSender = inject[ResetPasswordEmailSender]
        val (user, emailAddress) = db.readWrite { implicit rw =>
          val user = UserFactory.user().withName("Billy", "Madison").withUsername("test").saved
          val emailAddress = inject[UserEmailAddressCommander].intern(user.id.get, EmailAddress("billy@gmail.com")).get._1.address
          (user, emailAddress)
        }
        val email = Await.result(resetSender.sendToUser(user.id.get, emailAddress), Duration(5, "seconds"))
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
    //implicit val config = PublicIdConfiguration("secret key")

    def setup(withDescription: Boolean = true)(implicit injector: Injector) = {
      val keepRepo = inject[KeepRepo]
      val uriRepo = inject[NormalizedURIRepo]

      db.readWrite { implicit rw =>
        val user1 = UserFactory.user().withName("Tom", "Brady").withUsername("tom").saved
        val user2 = UserFactory.user().withName("Aaron", "Rodgers").withUsername("aaron").saved
        userEmailAddressCommander.intern(user1.id.get, EmailAddress("tombrady@gmail.com")).get
        userEmailAddressCommander.intern(user2.id.get, EmailAddress("aaronrodgers@gmail.com")).get
        val lib1 = libraryRepo.save(Library(name = "Football", ownerId = user1.id.get, slug = LibrarySlug("football"),
          visibility = LibraryVisibility.SECRET, memberCount = 1, description = { if (withDescription) { Some("Lorem ipsum") } else { None } }))

        val uri = uriRepo.save(NormalizedURI(url = "http://www.kifi.com", urlHash = UrlHash("abc")))
        // todo(andrew) jared compiler bug if url_ var is named url
        val keep = KeepFactory.keep().withLibrary(lib1).withUri(uri).withUser(user1).saved

        libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

        val invite = LibraryInvite(libraryId = lib1.id.get, inviterId = user1.id.get, access = LibraryAccess.READ_ONLY, message = None, authToken = "abcdefg")

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

    "sends 'html-rich' follow invite to user (userId)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteUser = invite.copy(userId = user2.id, message = Some("check this out!"))
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]

        val email = Await.result(inviteSender.sendInvite(invite = inviteUser, isPlainEmail = false), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.LIBRARY_INVITATION)
        email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === "support@kifi.com"
        email.subject === "Invite to join my Kifi library on Football"
        email.htmlBody.contains("http://dev.ezkeep.com:9000/tom/football?") === true
        email.htmlBody.contains("<span style=\"color:#999999\">Tom Brady</span>") === true
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kcid=na-vf_email-library_invite", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true, true)
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value
        testHtml(html)
      }
    }

    "send 'html-rich' follow invite to non-user (email)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteNonUser = invite.copy(emailAddress = Some(EmailAddress("aaronrodgers@gmail.com")), message = Some("check this out!"))
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]
        val email = Await.result(inviteSender.sendInvite(invite = inviteNonUser, isPlainEmail = false), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.NonUser.LIBRARY_INVITATION)
        email.subject === "Invite to join my Kifi library on Football"
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true)
        val html = email.htmlBody.value
        testHtml(html)

        db.readWrite { implicit session => libraryRepo.save(lib1.copy(visibility = LibraryVisibility.PUBLISHED)) }
        val emailWithoutPassPhrase = Await.result(inviteSender.sendInvite(invite = inviteNonUser, isPlainEmail = false), Duration(5, "seconds")).get
        emailWithoutPassPhrase.subject === "Invite to join my Kifi library on Football"
        emailWithoutPassPhrase.to(0) === EmailAddress("aaronrodgers@gmail.com")
        params.map(emailWithoutPassPhrase.htmlBody.contains(_)) === List(true, true, true, true)
        val htmlWithoutPassPhrase = emailWithoutPassPhrase.htmlBody.value
        testHtml(htmlWithoutPassPhrase)
      }
    }

    "send 'html-plain' follow invite to user (userId)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteUser = invite.copy(userId = user2.id)
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]

        val email = Await.result(inviteSender.sendInvite(invite = inviteUser, isPlainEmail = true), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"lv","lid":"${Library.publicId(lib1.id.get).id}","at":"${inviteUser.authToken}"}""", "ascii")

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.LIBRARY_INVITATION)
        email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === "support@kifi.com"
        email.subject === "Invite to join my Kifi library on Football"
        email.htmlBody.value must contain(deepLink)
        email.htmlBody.value must contain("Hi Aaron")
        email.htmlBody.value must contain("Check out the \"Football\" library")
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kcid=na-vf_email-library_invite", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true, true)
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value
        val scalaWords = Seq("firstName", "libraryUrl", "salutation", "emailMessage", "inviteMsg")
        scalaWords foreach { word => html must not contain word }
        1 === 1
      }
    }

    "send 'html-plain' collab invite to user (userId)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteUser = invite.copy(userId = user2.id, access = LibraryAccess.READ_WRITE)
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]

        val email = Await.result(inviteSender.sendInvite(invite = inviteUser, isPlainEmail = true), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"lv","lid":"${Library.publicId(lib1.id.get).id}","at":"${inviteUser.authToken}"}""", "ascii")

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.LIBRARY_INVITATION)
        email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === "support@kifi.com"
        email.subject === "Invite to collaborate on my Kifi library Football"
        email.htmlBody.value must contain(deepLink)
        email.htmlBody.value must contain("Hi Aaron")
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kcid=na-vf_email-library_invite", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true, true)
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value

        val text = email.textBody.get.value
        text must not contain "description"
      }
    }

    "send 'html-plain' follow invite to non-user (email)" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteNonUser = invite.copy(emailAddress = Some(EmailAddress("aaronrodgers@gmail.com")))
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]
        val email = Await.result(inviteSender.sendInvite(invite = inviteNonUser, isPlainEmail = true), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.NonUser.LIBRARY_INVITATION)
        email.subject === "Invite to join my Kifi library on Football"
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, false)
        val html = email.htmlBody.value
        html must not contain "firstName"

        db.readWrite { implicit session => libraryRepo.save(lib1.copy(visibility = LibraryVisibility.PUBLISHED)) }
        val emailWithoutPassPhrase = Await.result(inviteSender.sendInvite(invite = inviteNonUser, isPlainEmail = true), Duration(5, "seconds")).get
        emailWithoutPassPhrase.subject === "Invite to join my Kifi library on Football"
        emailWithoutPassPhrase.to(0) === EmailAddress("aaronrodgers@gmail.com")
        params.map(emailWithoutPassPhrase.htmlBody.contains(_)) === List(true, true, true, false)
      }
    }

    "send a stripped html-plain email when an invite message is included" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup()
        val inviteUser = invite.copy(userId = user2.id, message = Some("check this out!"))
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]

        val email = Await.result(inviteSender.sendInvite(invite = inviteUser, isPlainEmail = true), Duration(5, "seconds")).get

        outbox.size === 1
        outbox(0) === email

        val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"lv","lid":"${Library.publicId(lib1.id.get).id}","at":"${inviteUser.authToken}"}""", "ascii")

        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.LIBRARY_INVITATION)
        email.extraHeaders.get(PostOffice.Headers.REPLY_TO) === "support@kifi.com"
        email.subject === "Invite to join my Kifi library on Football"
        email.htmlBody.value must contain(deepLink)
        email.htmlBody.value must contain("check this out!")
        val params = List("utm_campaign=na", "utm_source=library_invite", "utm_medium=vf_email", "kcid=na-vf_email-library_invite", "kma=1")
        params.map(email.htmlBody.contains(_)) === List(true, true, true, true, true)
        email.to(0) === EmailAddress("aaronrodgers@gmail.com")
        val html = email.htmlBody.value
        val scalaWords = Seq("firstName")
        scalaWords foreach { word => html must not contain word }
        1 === 1
      }
    }

    "not include a library description when there is none" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup(withDescription = false)

        val inviteUser = invite.copy(userId = user2.id)
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]

        val email = Await.result(inviteSender.sendInvite(invite = inviteUser, isPlainEmail = true), Duration(5, "seconds")).get

        val html = email.htmlBody.value
        html must not contain "Here's what it's about:"
      }
    }

    "not include any code in the email" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, lib1, invite) = setup(withDescription = false)

        val inviteUser = invite.copy(userId = user2.id)
        val outbox = inject[FakeOutbox]
        val inviteSender = inject[LibraryInviteEmailSender]

        val email = Await.result(inviteSender.sendInvite(invite = inviteUser, isPlainEmail = true), Duration(5, "seconds")).get

        val html = email.htmlBody.value
        val text = email.textBody.get.value
        val scalaWords = Seq("firstName", "libraryUrl", "salutation", "emailMessage", "inviteMsg")
        scalaWords foreach { word => html must not contain word }
        scalaWords foreach { word => text must not contain word }
        1 === 1
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
          UserFactory.user().withName("Rocky", "Balboa").withUsername("tester").saved
        }
        val email = Await.result(sender.sendToUser(toEmail, user.id.get, "https://www.kifi.com/random/things-i-share", 123, "MY_KEY"), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(toEmail)
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.WAITLIST)
        email.subject === """Done! Your Twitter Library is ready with 123 keeps. Want Your “Liked” Links too?"""
        val html = email.htmlBody.value
        html must contain("Hi Rocky")
        html must contain("Your Twitter integrated library is ready with 123 keeps!")
        html must contain("""<a href="https://www.kifi.com/random/things-i-share">https://www.kifi.com/random/things-i-share</a>""")
        html must contain("https://twitter.com/intent/tweet?text=Browse%2Fsearch%20all%20the%20links%20I%E2%80%99ve%20shared%20on%20Twitter%20https%3A%2F%2Fwww.kifi.com%2Frandom%2Fthings-i-share%20via%20%40Kifi.%20Create%20your%20own%3A&url=https%3A%2F%2Fwww.kifi.com%2Ftwitter&source=kifi&related=kifi")
        html must not contain ("Your recommendations network") // custom email layout

        //        val text = email.textBody.get.value
        //        text must contain("Kifi Twitter library is ready")
      }
    }

    "sends confirmation email to existing old users" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val sender = inject[TwitterWaitlistOldUsersEmailSender]
        val toEmail = EmailAddress("foo@bar.com")
        val user = db.readWrite { implicit s =>
          UserFactory.user().withName("Rocky", "Balboa").withUsername("tester").saved
        }
        val email = Await.result(sender.sendToUser(toEmail, user.id.get, "https://www.kifi.com/random/things-i-share", 123, "MY_KEY"), Duration(5, "seconds"))
        outbox.size === 1
        outbox(0) === email

        email.to === Seq(toEmail)
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.User.WAITLIST)
        email.subject === """Your Twitter Library is building up with 123 keeps! Want Your “Liked” Links too?"""
        val html = email.htmlBody.value
        //        println(html)
        html must contain("Hi Rocky")
        html must contain("Kifi has new Twitter integration feature!")
        html must contain("""<a href="https://www.kifi.com/random/things-i-share">Twitter #deepsearch</a>""")
        html must contain("""<a href="https://www.kifi.com/twitter/sync-favorites?k=MY_KEY" target="_blank" style="color:#ffffff; text-decoration:none; line-height:20px; display:block;">Add “Your Liked Tweets” library</a>""")
        html must not contain ("Your recommendations network") // custom email layout

        //        val text = email.textBody.get.value
        //        text must contain("Kifi Twitter library is ready")
      }
    }
  }
}
