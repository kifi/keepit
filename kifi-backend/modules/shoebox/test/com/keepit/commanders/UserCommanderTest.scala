package com.keepit.commanders

import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.common.db.Id
import org.specs2.mutable.Specification

import com.keepit.test.{ ShoeboxTestInjector, ShoeboxApplication }
import com.keepit.model._
import com.keepit.common.mail._
import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.common.store.FakeShoeboxStoreModule

import play.api.test.Helpers.running

import com.google.inject.Injector
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule
import scala.concurrent.duration.Duration
import scala.concurrent.Await

class UserCommanderTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val userRepo = inject[UserRepo]
    val emailRepo = inject[UserEmailAddressRepo]
    val connectionRepo = inject[UserConnectionRepo]

    db.readWrite { implicit session =>
      var user1 = userRepo.save(User(
        firstName = "Homer",
        lastName = "Simpson"
      ))
      var user2 = userRepo.save(User(
        firstName = "Peter",
        lastName = "Griffin"
      ))
      var user3 = userRepo.save(User(
        firstName = "Clark",
        lastName = "Kent"
      ))

      val email1 = emailRepo.save(UserEmailAddress(userId = user1.id.get, address = EmailAddress("username@42go.com")))
      val email2 = emailRepo.save(UserEmailAddress(userId = user2.id.get, address = EmailAddress("peteg@42go.com")))
      val email3 = emailRepo.save(UserEmailAddress(userId = user3.id.get, address = EmailAddress("superreporter@42go.com")))

      user1 = userRepo.save(user1.copy(primaryEmail = Some(email1.address), pictureName = Some("dfkjiyert")))
      user2 = userRepo.save(user2.copy(primaryEmail = Some(email2.address)))

      connectionRepo.addConnections(user1.id.get, Set(user2.id.get, user3.id.get))
      (user1, user2, user3)
    }

  }

  val modules = Seq(
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule()
  )

  "UserCommander" should {

    "welcome a joinee" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]
        outbox.size === 0
        userCommander.sendWelcomeEmail(user1)
        outbox.size === 1
        outbox.all.filter(email => email.to.length == 1 && email.to.head.address == "username@42go.com").length === 1
        //double seding protection
        userCommander.sendWelcomeEmail(user1)
        outbox.size === 1

        //content check
        outbox(0).htmlBody.toString.containsSlice("Hey " + user1.firstName + ",") === true
        outbox(0).to.length === 1
        outbox(0).to(0).address === "username@42go.com"
        outbox(0).subject === "Let's get started with Kifi"

        outbox(0).htmlBody.toString.containsSlice("www.kifi.com/unsubscribe/") === true

      }
    }

    "page through connections" in {
      withDb(modules: _*) { implicit injector =>
        val userRepo = inject[UserRepo]
        val connectionRepo = inject[UserConnectionRepo]

        val (user1, user2, user3, user4) = db.readWrite {
          implicit session =>
            var user1 = userRepo.save(User(
              firstName = "Homer",
              lastName = "Simpson"
            ))
            var user2 = userRepo.save(User(
              firstName = "Peter",
              lastName = "Griffin"
            ))
            var user3 = userRepo.save(User(
              firstName = "Clark",
              lastName = "Kent"
            ))
            var user4 = userRepo.save(User(
              firstName = "Clark",
              lastName = "Simpson"
            ))

            connectionRepo.addConnections(user1.id.get, Set(user2.id.get, user3.id.get, user4.id.get))
            connectionRepo.addConnections(user2.id.get, Set(user4.id.get))
            (user1, user2, user3, user4)
        }
        val (connections1, total1) = inject[UserCommander].getConnectionsPage(user1.id.get, 0, 1000)
        connections1.size === 3
        total1 === 3

        val (connections2, total2) = inject[UserCommander].getConnectionsPage(user2.id.get, 0, 1000)
        connections2.size === 2
        total2 === 2

        val (connections1p1, total1p1) = inject[UserCommander].getConnectionsPage(user1.id.get, 1, 2)
        connections1p1.size === 1
        connections1p1.head.userId === user4.id.get
        total1p1 === 3

        inject[UserCommander].getConnectionsPage(user1.id.get, 2, 2)._1.size === 0
      }
    }

    "send a close account email" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]

        outbox.size === 0
        // test that the angle brackets are removed
        userCommander.sendCloseAccountEmail(user1.id.get, "<a>l</a>going amish")
        outbox.size === 1

        val mail: ElectronicMail = outbox(0)
        mail.from === SystemEmailAddress.ENG
        mail.to === Seq(SystemEmailAddress.SUPPORT)
        mail.subject.toString === s"Close Account for ${user1.id.get}"
        mail.htmlBody.toString === s"User ${user1.id.get} requested to close account.<br/>---<br/>al/agoing amish"
        mail.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.System.ADMIN)
      }
    }

    "normalize usernames" in {
      UsernameOps.normalize("léo") === "leo"
      UsernameOps.normalize("andrew.conner2") === "andrewconner2"
      UsernameOps.normalize("康弘康弘") === "康弘康弘"
      UsernameOps.normalize("ân_dréw-c.ön.nér") === "andrewconner"
    }

    "allow change of username" in {
      withDb(modules: _*) { implicit injector =>
        val userCommander = inject[UserCommander]
        val (user1, user2, user3) = setup()

        // basic changing, no dupes
        userCommander.setUsername(user1.id.get, Username("bobz"), false) === Right(Username("bobz"))
        userCommander.setUsername(user2.id.get, Username("bob.z"), false) === Left("username_exists")
        userCommander.setUsername(user1.id.get, Username("bob.z"), false) === Right(Username("bob.z"))

        // changes user model
        db.readOnlyMaster(s => userRepo.get(user2.id.get)(s).username === None)
        userCommander.setUsername(user2.id.get, Username("obama"), false) === Right(Username("obama"))
        db.readOnlyMaster(s => userRepo.get(user2.id.get)(s).username.get === Username("obama"))

        // filter out invalid names
        userCommander.setUsername(user3.id.get, Username("a.-bc"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username(".abc3"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("kifisupport"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("mayihelpyou"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("abcd?"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("abcd?"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("abcd?"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("amazon"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("yes"), false) === Left("invalid_username")
        userCommander.setUsername(user3.id.get, Username("aes.corp"), false) === Left("invalid_username")

      }
    }

    "tellContactsAboutNewUser" should {
      // await wrapper about the commander method call
      def tellContactsAboutNewUser(user: User)(implicit injector: Injector): Set[Id[User]] =
        Await.result(inject[UserCommander].tellUsersWithContactOfNewUserImmediate(user).get, Duration(5, "seconds"))

      "send notifications to all users connected to a user's email" in withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val outbox = inject[FakeOutbox]
        val user4 = db.readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "Jane", lastName = "Doe", primaryEmail = Some(EmailAddress("jane@doe.com"))))
        }

        // set service client response
        inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl].contactsConnectedToEmailAddress =
          Set(user2, user3, user4).map(_.id.get)

        val userExpCommander = inject[LocalUserExperimentCommander]
        userExpCommander.addExperimentForUser(user4.id.get, ExperimentType.NOTIFY_USER_WHEN_CONTACTS_JOIN)

        outbox.size === 0
        tellContactsAboutNewUser(user1) === Set(user4.id.get)
        outbox.size === 1

        // test the personalized parts of the email
        val mail = outbox(0)
        mail.subject must beEqualTo("Homer Simpson joined Kifi. Want to connect?")

        val htmlBody = mail.htmlBody.value
        htmlBody must contain("Hey Jane")
        htmlBody must contain("welcome Homer and add him")
        htmlBody must contain("Add Homer")
        htmlBody must contain("/invite?friend=" + user1.externalId)

        val textBody = mail.textBody.get.value
        textBody must contain("Hey Jane")
        textBody must contain("welcome Homer and add him")
        textBody must contain("/invite?friend=" + user1.externalId)

        NotificationCategory.fromElectronicMailCategory(mail.category) must beEqualTo(NotificationCategory.User.CONTACT_JOINED)
      }

      "do nothing if no users are connected to a user's email" in withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val outbox = inject[FakeOutbox]

        // set service client response
        inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl].contactsConnectedToEmailAddress = Set.empty

        outbox.size === 0
        tellContactsAboutNewUser(user1) === Set.empty
        outbox.size === 0
      }
    }
  }
}
