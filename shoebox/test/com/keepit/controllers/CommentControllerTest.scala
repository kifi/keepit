package com.keepit.controllers

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import play.api.test.FakeHeaders
import com.keepit.inject._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.test.FakeClock
import com.keepit.common.mail._

@RunWith(classOf[JUnitRunner])
class CommentControllerTest extends SpecificationWithJUnit {

  "CommentController" should {

    "replace link" in {
      CommentController.replaceLookHereLinks("[hi there](x-kifi-sel:body>foo.bar#there)") === "[hi there]"
      CommentController.replaceLookHereLinks("A [hi there](x-kifi-sel:foo.bar#there) B") === "A [hi there] B"
      CommentController.replaceLookHereLinks("(A) [hi there](x-kifi-sel:foo.bar#there:nth-child(2\\)>a:nth-child(1\\)) [B] C") === "(A) [hi there] [B] C"
    }

    "persist comment emails" in {
      running(new EmptyApplication().withFakeMail()) {
        val comment = inject[DBConnection].readWrite { implicit s =>
          val userRepo = inject[UserRepo]
          val emailRepo = inject[EmailAddressRepo]
          val normalizedURIRepo = inject[NormalizedURIRepo]
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
          val recepient = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
          emailRepo.save(EmailAddress(userId = recepient.id.get, verifiedAt = Some(currentDateTime), address = "eishay@42go.com"))
          val uri = normalizedURIRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
          val msg = inject[CommentRepo].save(Comment(uriId = uri.id.get, userId = user.id.get, pageTitle = "My Title",
            text = """Public Comment [look here](x-kifi-sel:body>div#page-container>div.column-container>div.left-container>div#module-post-detail.module-post-detail.__FIRST__.image>div.body-copy) on Google1""",
            permissions = CommentPermissions.MESSAGE))
          inject[CommentRecipientRepo].save(CommentRecipient(commentId = msg.id.get, userId = Some(recepient.id.get)))
          msg
        }
        CommentController.notifyRecipients(comment)
        val mails = inject[FakeOutbox]
        mails.size === 1
        val mail = mails.head
        mail.senderUserId.get === comment.userId
        mail.subject === "Andrew Conner sent you a message using KiFi"
        mail.htmlBody.value must contain("""Public Comment [look here] on Google1""")
      }
    }
  }
}
