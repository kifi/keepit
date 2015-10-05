package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UserCommanderTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val emailCommander = inject[UserEmailAddressCommander]
    val connectionRepo = inject[UserConnectionRepo]

    db.readWrite { implicit session =>
      val user1 = UserFactory.user().withName("Homer", "Simpson").withUsername("homer").withPictureName("dfkjiyert").saved
      val user2 = UserFactory.user().withName("Peter", "Griffin").withUsername("peter").saved
      val user3 = UserFactory.user().withName("Clark", "Kent").withUsername("clark").saved

      emailCommander.intern(user1.id.get, EmailAddress("username@42go.com"), verified = true).get
      emailCommander.intern(user2.id.get, EmailAddress("peteg@42go.com"), verified = true).get
      emailCommander.intern(user3.id.get, EmailAddress("superreporter@42go.com"), verified = true).get

      val clock = inject[FakeClock]
      val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
      clock.push(now)
      clock.push(now.plusDays(1))
      clock.push(now.plusDays(2))
      clock.push(now.plusDays(3))
      clock.push(now.plusDays(4))
      connectionRepo.addConnections(user1.id.get, Set(user2.id.get, user3.id.get))
      (user1, user2, user3)
    }

  }

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule()
  )

  "UserCommander" should {

    "welcome a joinee with a html-rich email" in {

      val WELCOME_SUBJECT = "Let's get started with Kifi"
      def WELCOME_SALUTATION(firstName: String) = "Hey " + firstName + ","
      val WELCOME_SENDER = "Eishay Smith"
      val WELCOME_SENDER_EMAIL = SystemEmailAddress.EISHAY_PUBLIC

      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]
        val userAddress = EmailAddress("username@42go.com")

        outbox.size === 0
        Await.ready(userCommander.sendWelcomeEmail(newUser = user1, isPlainEmail = false), Duration(5, "seconds"))
        outbox.size === 1
        outbox.all.count(email => email.to.length == 1 && email.to.head == userAddress) === 1

        //double sending protection
        Await.ready(userCommander.sendWelcomeEmail(newUser = user1, isPlainEmail = false), Duration(5, "seconds"))
        outbox.size === 1

        outbox(0).to === Seq(userAddress)
        outbox(0).subject === WELCOME_SUBJECT
        outbox(0).from === WELCOME_SENDER_EMAIL

        val html = outbox(0).htmlBody.value
        html must contain(WELCOME_SALUTATION(user1.firstName))
      }
    }

    "welcome a joinee with a html-plain email" in {
      val WELCOME_SUBJECT = "Let's get started with Kifi"
      def WELCOME_SALUTATION(firstName: String) = "Dear " + firstName + ","
      val WELCOME_SENDER = "Eishay Smith"
      val WELCOME_SENDER_EMAIL = SystemEmailAddress.EISHAY_PUBLIC

      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]
        val userAddress = EmailAddress("username@42go.com")

        outbox.size === 0
        Await.ready(userCommander.sendWelcomeEmail(newUser = user1, isPlainEmail = true), Duration(5, "seconds"))
        outbox.size === 1
        outbox.all.count(email => email.to.length == 1 && email.to.head == userAddress) === 1

        //double sending protection
        Await.ready(userCommander.sendWelcomeEmail(newUser = user1, isPlainEmail = true), Duration(5, "seconds"))
        outbox.size === 1

        outbox(0).to === Seq(userAddress)
        outbox(0).subject === WELCOME_SUBJECT
        outbox(0).from === WELCOME_SENDER_EMAIL

        val html = outbox(0).htmlBody.value
        html must contain(WELCOME_SALUTATION(user1.firstName))
        html must contain(WELCOME_SENDER)
      }
    }

    "page through connections" in {
      withDb(modules: _*) { implicit injector =>
        val userRepo = inject[UserRepo]
        val connectionRepo = inject[UserConnectionRepo]

        val (user1, user2, user3) = setup()
        val user4 = db.readWrite { implicit session =>
          var user4 = UserFactory.user().withName("Jess", "Jones").withUsername("jess").saved
          connectionRepo.addConnections(user1.id.get, Set(user3.id.get, user2.id.get, user4.id.get))
          connectionRepo.addConnections(user2.id.get, Set(user4.id.get))
          user4
        }

        val (connections2, total2) = inject[UserConnectionsCommander].getConnectionsPage(user2.id.get, 0, 1000)
        connections2.size === 2
        total2 === 2

        val (connections1p1, total1p1) = inject[UserConnectionsCommander].getConnectionsPage(user1.id.get, 1, 2)
        connections1p1.size === 1
        total1p1 === 3

        inject[UserConnectionsCommander].getConnectionsPage(user1.id.get, 2, 2)._1.size === 0
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

    "allow change of username" in {
      withDb(modules: _*) { implicit injector =>
        val userCommander = inject[UserCommander]
        val (user1, user2, user3) = setup()

        // basic changing, no dupes
        userCommander.setUsername(user1.id.get, Username("bobz"), false) === Right(Username("bobz"))
        userCommander.setUsername(user2.id.get, Username("bob.z"), false) === Left("username_exists")
        userCommander.setUsername(user1.id.get, Username("bob.z"), false) === Right(Username("bob.z"))

        // changes user model
        db.readOnlyMaster(s => userRepo.get(user2.id.get)(s).username.value === "peter")
        userCommander.setUsername(user2.id.get, Username("obama"), false) === Right(Username("obama"))
        db.readOnlyMaster(s => userRepo.get(user2.id.get)(s).username === Username("obama"))

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
      def tellContactsAboutNewUser(user: User)(implicit injector: Injector): Set[Id[User]] = {
        Await.result(inject[UserCommander].tellUsersWithContactOfNewUserImmediate(user).get, Duration(5, "seconds"))
      }

      "send notifications to all users connected to a user's email" in withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = setup()
        val outbox = inject[FakeOutbox]
        val user4 = db.readWrite { implicit rw =>
          val user4 = UserFactory.user().withName("Jane", "Doe").withUsername("jane").saved
          inject[UserEmailAddressCommander].intern(user4.id.get, EmailAddress("jane@doe.com"), verified = true)
          user4
        }

        // set service client response
        inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl].contactsConnectedToEmailAddress =
          Set(user2, user3, user4).map(_.id.get)

        outbox.size === 0
        tellContactsAboutNewUser(user1) === Set(user4.id.get)
        outbox.size === 1

        // test the personalized parts of the email
        val mail = outbox(0)
        mail.subject must beEqualTo("Homer Simpson joined Kifi. Want to connect?")

        val htmlBody = mail.htmlBody.value
        htmlBody must contain("Hi Jane")
        htmlBody must contain("Homer Simpson just joined")
        htmlBody must contain("/homer?intent=connect")

        val textBody = mail.textBody.get.value
        textBody must contain("Hi Jane")
        textBody must contain("Homer Simpson just joined")
        textBody must contain("/homer?intent=connect")

        NotificationCategory.fromElectronicMailCategory(mail.category) === NotificationCategory.User.CONTACT_JOINED
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
