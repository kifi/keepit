package com.keepit.controllers

import com.keepit.common.db.CX
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
      val uri = NormalizedURI("http://42go.com/go")
      CommentController.replaceLookHereLinks("[hi there](x-kifi-sel:body>foo.bar#there)", uri) === """[hi there]"""
      CommentController.replaceLookHereLinks("A [hi there](x-kifi-sel:foo.bar#there) B", uri) === """A [hi there] B"""
      CommentController.replaceLookHereLinks("(A) [hi there](x-kifi-sel:foo.bar#there:nth-child(2\\)>a) [B] C", uri) === """(A) [hi there] [B] C"""
    }

    "persist comment emails" in {
      running(new EmptyApplication().withFakeMail()) {
        val comment = CX.withConnection { implicit conn =>
          val user = User(firstName = "Andrew", lastName = "Conner").save
          val recepient = User(firstName = "Eishay", lastName = "Smith").save
          EmailAddress(userId = recepient.id.get, verifiedAt = Some(currentDateTime), address = "eishay@42go.com").save
          val uri = NormalizedURI("Google", "http://www.google.com/").save
          val msg = Comment(uriId = uri.id.get, userId = user.id.get, pageTitle = "My Title",
            text = """Public Comment [look here](x-kifi-sel:body>div#page-container>div.column-container>div.left-container>div#module-post-detail.module-post-detail.__FIRST__.image>div.body-copy) on Google1""",
            permissions = Comment.Permissions.MESSAGE).save
          CommentRecipient(commentId = msg.id.get, userId = Some(recepient.id.get)).save
          msg
        }
        CommentController.notifyRecipients(comment)
        val mails = inject[FakeOutbox]
        mails.size === 1
        val mail = mails.head
        mail.subject === "Andrew Conner sent you a message using KiFi"
        println(mail.htmlBody)
        mail.htmlBody.contains("""Public Comment [look here] on Google1""") === true
      }
    }
  }

}
