package com.keepit.controllers

import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.common.mail._
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.controllers.ext.ExtCommentController
import com.keepit.inject._
import com.keepit.model._
import com.keepit.test.DbRepos
import com.keepit.test.EmptyApplication
import com.keepit.test.FakeClock
import play.api.Play.current
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.realtime.UserEmailNotifierPluginImpl
import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers.running

class CommentControllerTest extends TestKit(ActorSystem()) with Specification with DbRepos {

  "CommentController" should {

    "follow and unfollow" in {
      running(new EmptyApplication().withFakeSecureSocialUserService().withFakeMail().withFakeStore()) {
        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val oAuth2Info = OAuth2Info(accessToken = "A", tokenType = None, expiresIn = None, refreshToken = None)
        val su = SocialUser(UserId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        db.readWrite { implicit s =>
          val user = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1"))
          val sui = socialUserInfoRepo.save(SocialUserInfo(
              userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su)))
          user
        }

        val cookie = Authenticator.create(su).right.get.toCookie
        val request1 = FakeRequest("POST", "/comments/follow")
          .withCookies(cookie)
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result1 = route(request1).get
        status(result1) === OK
        contentAsString(result1) === """{"following":true}"""

        val request2 = FakeRequest("POST", "/comments/unfollow")
          .withCookies(cookie)
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result2 = route(request2).get
        status(result2) === OK
        contentAsString(result2) === """{"following":false}"""

        val request3 = FakeRequest("POST", "/comments/follow")
          .withCookies(cookie)
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result3 = route(request3).get
        status(result3) === OK
        contentAsString(result3) === """{"following":true}"""

        val request4 = FakeRequest("POST", "/comments/follow")
          .withCookies(cookie)
          .withJsonBody(JsObject(Seq("url" -> JsString("http://www.42go.com"))))
        val result4 = route(request4).get
        status(result4) === OK
        contentAsString(result4) === """{"following":true}"""      }
    }

    "persist comment emails" in {
      running(new EmptyApplication().withFakeMail().withTestActorSystem(system)) {
        val comment = db.readWrite { implicit s =>
          val userRepo = inject[UserRepo]
          val emailRepo = inject[EmailAddressRepo]
          val normalizedURIRepo = inject[NormalizedURIRepo]
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
          val recepient = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
          emailRepo.save(EmailAddress(userId = recepient.id.get, verifiedAt = Some(currentDateTime), address = "eishay@42go.com"))
          userExperimentRepo.save(UserExperiment(userId = recepient.id.get, experimentType = ExperimentTypes.ADMIN))
          val uri = normalizedURIRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
          val msg = inject[CommentRepo].save(Comment(uriId = uri.id.get, userId = user.id.get, pageTitle = "My Title",
            text = """Public Comment [look here](x-kifi-sel:body>div#page-container>div.column-container>div.left-container>div#module-post-detail.module-post-detail.__FIRST__.image>div.body-copy) on Google1""",
            permissions = CommentPermissions.MESSAGE))
          inject[CommentRecipientRepo].save(CommentRecipient(commentId = msg.id.get, userId = Some(recepient.id.get)))
          msg
        }
        val extCommentController = inject[ExtCommentController]
        extCommentController.notifyRecipients(comment)

        inject[FakeClock].push(new DateTime("2016-01-01"))
        inject[UserEmailNotifierPluginImpl].sendEmails()

        val mails = inject[FakeOutbox]
        val mail = mails.head
        mail.htmlBody.value must contain("""Public Comment [look here] on Google1""")
        mails.size === 1
      }
    }
  }
}
