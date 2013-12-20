package com.keepit.commanders

import org.specs2.mutable.Specification

import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.{User, EmailAddressRepo, UserRepo, EmailAddress, UserConnectionRepo}
import com.keepit.common.mail.{FakeMailModule, FakeOutbox}
import com.keepit.abook.TestABookServiceClientModule

import com.google.inject.Injector

class UserCommanderTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val userRepo = inject[UserRepo]
    val emailRepo = inject[EmailAddressRepo]
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


      val email1 = emailRepo.save(EmailAddress(userId=user1.id.get, address="username@42go.com"))
      val email2 = emailRepo.save(EmailAddress(userId=user2.id.get, address="peteG@42go.com"))
      val email3 = emailRepo.save(EmailAddress(userId=user3.id.get, address="superreporter@42go.com"))

      user1 = userRepo.save(user1.copy(primaryEmailId = Some(email1.id.get)))
      user2 = userRepo.save(user2.copy(primaryEmailId = Some(email2.id.get)))

      connectionRepo.addConnections(user1.id.get, Set(user2.id.get, user3.id.get))
      (user1, user2, user3)
    }



  }

  "UserCommander" should {

    "notify friends of new joinee" in {
      withDb(FakeMailModule(),TestABookServiceClientModule()) { implicit injector =>
        val (user1, user2, user3) = setup()
        val userCommander = inject[UserCommander]
        val outbox = inject[FakeOutbox]
        outbox.size === 0
        userCommander.tellAllFriendsAboutNewUser(user1.id.get, Seq(user2.id.get))
        outbox.size === 2
        val forUser2 = outbox.all.filter( email => email.to.length==1 && email.to.head.address=="peteG@42go.com")
        val forUser3 = outbox.all.filter( email => email.to.length==1 && email.to.head.address=="superreporter@42go.com")
        forUser2.length===1
        forUser3.length===1
        //double seding protection
        userCommander.tellAllFriendsAboutNewUser(user1.id.get, Seq(user2.id.get))
        outbox.size === 2
        //ZZZ make sure to test for correct content once that is available (i.e. html is done)
      }
    }

    "welcome a joinee" in {
      withDb(FakeMailModule(),TestABookServiceClientModule()) { implicit injector =>
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
         //ZZZ make sure to test for correct content once that is available (i.e. html is done)
      }
    }


  }

}
