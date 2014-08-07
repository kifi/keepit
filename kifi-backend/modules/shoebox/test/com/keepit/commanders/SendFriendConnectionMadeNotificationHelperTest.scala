package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail._
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class SendFriendConnectionMadeNotificationHelperTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val userRepo = inject[UserRepo]
    val emailRepo = inject[UserEmailAddressRepo]
    val connectionRepo = inject[UserConnectionRepo]

    db.readWrite { implicit session =>
      val user1 = userRepo.save(User(
        firstName = "Homer",
        lastName = "Simpson",
        primaryEmail = Some(EmailAddress("homer@gmail.com"))
      ))
      val user2 = userRepo.save(User(
        firstName = "Peter",
        lastName = "Griffin",
        primaryEmail = Some(EmailAddress("peter@gmail.com"))
      ))

      (user1, user2)
    }
  }

  val modules = Seq(
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeExternalServiceModule()
  )

  "FriendConnectionMadeHelper" should {

    "sends email and notification to friend" in {
      withDb(modules: _*) { implicit injector =>
        implicit val ctx = scala.concurrent.ExecutionContext.global

        val outbox = inject[FakeOutbox]
        val (user1, user2) = setup()

        val helper = inject[SendFriendConnectionMadeNotificationHelper]
        helper(user2.id.get, user1.id.get).map { id => id } // map waits for the Future

        //content check
        val htmlBody: String = outbox(0).htmlBody.toString
        val textBody: String = outbox(0).textBody.get.toString

        outbox(0).subject === "You are now friends with Peter Griffin on Kifi!"
        outbox(0).to === Seq(EmailAddress("homer@gmail.com"))
        outbox(0).fromName === Some("Peter Griffin (via Kifi)")
        htmlBody must contain("with Peter Griffin on Kifi")
        htmlBody must contain("Enjoy Peter’s keeps")
        textBody must contain("with Peter Griffin on Kifi")
        textBody must contain("Enjoy Peter’s keeps")
        outbox.size === 1
      }
    }
  }
}
