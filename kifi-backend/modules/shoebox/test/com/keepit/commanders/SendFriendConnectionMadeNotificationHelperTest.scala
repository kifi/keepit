package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.mail._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.model.{ Username, User, UserRepo }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.Await

class SendFriendConnectionMadeNotificationHelperTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val userRepo = inject[UserRepo]

    db.readWrite { implicit session =>
      val user1 = userRepo.save(User(
        firstName = "Homer",
        lastName = "Simpson",
        primaryEmail = Some(EmailAddress("homer@gmail.com")), username = Username("test"), normalizedUsername = "test"
      ))
      val user2 = userRepo.save(User(
        firstName = "Peter",
        lastName = "Griffin",
        primaryEmail = Some(EmailAddress("peter@gmail.com")), username = Username("test"), normalizedUsername = "test"
      ))

      (user1, user2)
    }
  }

  val modules = Seq(
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule()
  )

  "FriendConnectionMadeHelper" should {

    "sends email and notification to friend" in {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val (user1, user2) = setup()

        val helper = inject[FriendConnectionNotifier]
        Await.ready(helper.sendNotification(user2.id.get, user1.id.get), Duration(5, "seconds"))

        //content check
        val htmlBody: String = outbox(0).htmlBody.toString
        val textBody: String = outbox(0).textBody.get.toString

        outbox(0).subject === "You and Peter Griffin are now connected on Kifi!"
        outbox(0).to === Seq(EmailAddress("homer@gmail.com"))
        outbox(0).fromName === Some("Peter Griffin (via Kifi)")
        htmlBody must contain("Hi Homer")
        htmlBody must contain("Peter Griffin")
        textBody must contain("Hi Homer")
        textBody must contain("Peter Griffin")
        outbox.size === 1
      }
    }
  }
}
