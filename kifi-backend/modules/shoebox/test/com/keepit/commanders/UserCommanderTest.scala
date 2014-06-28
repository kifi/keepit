package com.keepit.commanders

import org.specs2.mutable.Specification

import com.keepit.test.{ShoeboxApplicationInjector, ShoeboxApplication}
import com.keepit.model.{User, UserEmailAddressRepo, UserRepo, UserEmailAddress, UserConnectionRepo}
import com.keepit.common.mail.{EmailAddress, FakeMailModule, FakeOutbox}
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.scraper.{TestScraperServiceClientModule, FakeScrapeSchedulerModule}
import com.keepit.common.store.ShoeboxFakeStoreModule

import play.api.test.Helpers.running

import com.google.inject.Injector
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class UserCommanderTest extends Specification with ShoeboxApplicationInjector {

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


      val email1 = emailRepo.save(UserEmailAddress(userId=user1.id.get, address=EmailAddress("username@42go.com")))
      val email2 = emailRepo.save(UserEmailAddress(userId=user2.id.get, address=EmailAddress("peteg@42go.com")))
      val email3 = emailRepo.save(UserEmailAddress(userId=user3.id.get, address=EmailAddress("superreporter@42go.com")))

      user1 = userRepo.save(user1.copy(primaryEmail = Some(email1.address), pictureName = Some("dfkjiyert")))
      user2 = userRepo.save(user2.copy(primaryEmail = Some(email2.address)))

      connectionRepo.addConnections(user1.id.get, Set(user2.id.get, user3.id.get))
      (user1, user2, user3)
    }

  }

  val modules = Seq(
    FakeMailModule(),
    TestABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule()
  )

  "UserCommander" should {

    "notify friends of new joinee" in {
      running(new ShoeboxApplication(modules:_*)) {
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]
        outbox.size === 0
        userCommander.tellAllFriendsAboutNewUserImmediate(user1.id.get, Seq(user2.id.get))
        outbox.size === 2
        val forUser2 = outbox.all.filter( email => email.to.length==1 && email.to.head.address=="peteg@42go.com")
        val forUser3 = outbox.all.filter( email => email.to.length==1 && email.to.head.address=="superreporter@42go.com")
        forUser2.length===1
        forUser3.length===1
        //double seding protection
        userCommander.tellAllFriendsAboutNewUser(user1.id.get, Seq(user2.id.get))
        outbox.size === 2

        //content check
        outbox(0).htmlBody.toString.containsSlice(s"${user1.firstName} ${user1.lastName} just joined Kifi") === true
        outbox(1).htmlBody.toString.containsSlice(s"${user1.firstName} ${user1.lastName} just joined Kifi") === true

        outbox(0).htmlBody.toString.containsSlice(user1.pictureName.get) === true
        outbox(1).htmlBody.toString.containsSlice(user1.pictureName.get) === true

        outbox(0).htmlBody.toString.containsSlice(user2.firstName) === true
        outbox(1).htmlBody.toString.containsSlice(user3.firstName) === true

        outbox(0).subject === s"${user1.firstName} ${user1.lastName} joined Kifi"
        outbox(1).subject === s"${user1.firstName} ${user1.lastName} joined Kifi"

        outbox(0).to.length === 1
        outbox(1).to.length === 1

        outbox(0).to(0).address === "peteg@42go.com"
        outbox(1).to(0).address === "superreporter@42go.com"
      }
    }

    "welcome a joinee" in {
      running(new ShoeboxApplication(modules:_*)) {
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]
        outbox.size===0
        userCommander.sendWelcomeEmail(user1)
        outbox.size===1
        outbox.all.filter( email => email.to.length==1 && email.to.head.address=="username@42go.com").length===1
        //double seding protection
        userCommander.sendWelcomeEmail(user1)
        outbox.size===1

        //content check
        outbox(0).htmlBody.toString.containsSlice("Hey " + user1.firstName + ",") === true
        outbox(0).to.length === 1
        outbox(0).to(0).address === "username@42go.com"
        outbox(0).subject === "Let's get started with Kifi"

        outbox(0).htmlBody.toString.containsSlice("www.kifi.com/unsubscribe/") === true

      }
    }

    "page through connections" in {
      running(new ShoeboxApplication(modules:_*)) {
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
  }

}
