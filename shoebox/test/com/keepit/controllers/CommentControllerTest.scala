package com.keepit.controllers

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.mail.FakeOutbox
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.model.ExperimentTypes.ADMIN
import com.keepit.test.EmptyApplication
import com.keepit.test.FakeClock
import com.keepit.test.DbRepos

import org.junit.runner.RunWith
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner

import play.api.Play.current
import play.api.libs.json.{Json, JsArray, JsBoolean, JsObject, JsString, JsValue}
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import play.api.test.FakeHeaders

import securesocial.core.SecureSocial
import securesocial.core.OAuth2Info
import securesocial.core.SocialUser
import securesocial.core.UserId
import securesocial.core.AuthenticationMethod
import org.joda.time.DateTime

@RunWith(classOf[JUnitRunner])
class CommentControllerTest extends SpecificationWithJUnit with DbRepos {

  "CommentController" should {

    "follow and unfollow" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val user = db.readWrite { implicit s =>
          val user = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1"))
          val oAuth2Info = OAuth2Info(accessToken = "A", tokenType = None, expiresIn = None, refreshToken = None)
          val su = SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"),
            Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
          val sui = socialUserInfoRepo.save(SocialUserInfo(
              userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su)))
          user
        }

        val request1 = FakeRequest("POST", "/comments/follow")
          .withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook")
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result1 = routeAndCall(request1).get
        status(result1) === OK
        contentAsString(result1) === """{"following":true}"""

        val request2 = FakeRequest("POST", "/comments/unfollow")
          .withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook")
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result2 = routeAndCall(request2).get
        status(result2) === OK
        contentAsString(result2) === """{"following":false}"""

        val request3 = FakeRequest("POST", "/comments/follow")
          .withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook")
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result3 = routeAndCall(request3).get
        status(result3) === OK
        contentAsString(result3) === """{"following":true}"""

        val request4 = FakeRequest("POST", "/comments/follow")
          .withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook")
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result4 = routeAndCall(request4).get
        status(result4) === OK
        contentAsString(result4) === """{"following":true}"""      }
    }

    "replace links" in {
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
