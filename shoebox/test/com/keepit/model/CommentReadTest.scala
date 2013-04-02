package com.keepit.model

import org.specs2.mutable._
import com.keepit.test.{DbRepos, EmptyApplication}

import play.api.Play.current
import play.api.libs.json._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.LargeString._
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks

import com.keepit.controllers.admin.AdminDashboardController
import com.keepit.controllers.ext.ExtCommentController
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.controller.AuthenticatedRequest

import securesocial.core._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class CommentReadTest extends Specification with DbRepos {

  def setup() = {
    db.readWrite {implicit s =>
      val commentRepo = inject[CommentRepo]
      val userRepo = inject[UserRepo]
      val commentRecipientRepo = inject[CommentRecipientRepo]
      val normalizedURIRepo = inject[NormalizedURIRepo]

      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

      val uri1 = normalizedURIRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = normalizedURIRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))

      // Public
      val comment1 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google1", permissions = CommentPermissions.PUBLIC))
      val comment2 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google2", permissions = CommentPermissions.PUBLIC))
      val comment3 = commentRepo.save(Comment(uriId = uri2.id.get, userId = user1.id.get, pageTitle = uri2.title.get, text = "Public Comment on Bing", permissions = CommentPermissions.PUBLIC))

      // Messages
      val msg1 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google1", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg1.id.get, userId = Some(user2.id.get)))
      val msg2 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)))
      val msg3 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE))
      val msg4 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google4", permissions = CommentPermissions.MESSAGE, parent = msg3.id))

      (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3, msg4)
    }
  }

  "CommentRead" should {
    "keep track of read/unread comments" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3, msg4) = setup()

        db.readWrite { implicit s =>
          val userRepo = inject[UserRepo]
          val user3 = userRepo.save(User(firstName = "Other", lastName = "User"))

          val commentRepo = inject[CommentRepo]
          val commentReadRepo = inject[CommentReadRepo]

          val socialUserInfoRepo = inject[SocialUserInfoRepo]
          val socialUser1 = socialUserInfoRepo.save(SocialUserInfo(userId = user1.id, fullName = "Andrew Conner", socialId = SocialId("1111111"), networkType = SocialNetworks.FACEBOOK))
          val socialUser2 = socialUserInfoRepo.save(SocialUserInfo(userId = user2.id, fullName = "Eishay Smith", socialId = SocialId("2222222"), networkType = SocialNetworks.FACEBOOK))
          val socialConnRepo = inject[SocialConnectionRepo]
          socialConnRepo.save(SocialConnection(socialUser1 = socialUser1.id.get, socialUser2 = socialUser2.id.get))

          // Initially, all messages should be unread

          commentReadRepo.hasUnreadComments(user1.id.get, uri1.id.get) === true
          commentReadRepo.hasUnreadComments(user2.id.get, uri1.id.get) === true
          commentReadRepo.hasUnreadComments(user1.id.get, uri2.id.get) === false
          commentReadRepo.hasUnreadComments(user2.id.get, uri2.id.get) === true
          commentReadRepo.hasUnreadComments(user3.id.get, uri1.id.get) === false
          commentReadRepo.hasUnreadComments(user3.id.get, uri2.id.get) === false

          commentReadRepo.getByUserAndUri(user1.id.get, uri1.id.get).isDefined == false

          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = comment2.id.get))

          commentReadRepo.hasUnreadComments(user1.id.get, uri1.id.get) === false
          commentReadRepo.hasUnreadComments(user2.id.get, uri1.id.get) === true
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = comment2.id.get))
          commentReadRepo.hasUnreadComments(user2.id.get, uri1.id.get) === false
        }
      }
    }
    "keep track of read/unread messages" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3, msg4) = setup()

        db.readWrite { implicit s =>
          val userRepo = inject[UserRepo]
          val user3 = userRepo.save(User(firstName = "Other", lastName = "User"))

          val commentRepo = inject[CommentRepo]
          val commentReadRepo = inject[CommentReadRepo]

          val socialUserInfoRepo = inject[SocialUserInfoRepo]
          val socialUser1 = socialUserInfoRepo.save(SocialUserInfo(userId = user1.id, fullName = "Andrew Conner", socialId = SocialId("1111111"), networkType = SocialNetworks.FACEBOOK))
          val socialUser2 = socialUserInfoRepo.save(SocialUserInfo(userId = user2.id, fullName = "Eishay Smith", socialId = SocialId("2222222"), networkType = SocialNetworks.FACEBOOK))
          val socialConnRepo = inject[SocialConnectionRepo]
          socialConnRepo.save(SocialConnection(socialUser1 = socialUser1.id.get, socialUser2 = socialUser2.id.get))

          // Initially, all messages should be unread

          val messages = commentReadRepo.getParentsOfUnreadMessages(user1.id.get, uri1.id.get)
          messages.size === 2
          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = msg1.id.get, parentId = Some(msg1.id.get)))
          commentReadRepo.getParentsOfUnreadMessages(user1.id.get, uri1.id.get).size === 1
          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = msg2.id.get, parentId = Some(msg2.id.get)))
          commentReadRepo.getParentsOfUnreadMessages(user1.id.get, uri1.id.get).size === 0

          commentReadRepo.getParentsOfUnreadMessages(user2.id.get, uri1.id.get).size === 3
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = messages(1).id.get, parentId = Some(messages(1).id.get)))
          commentReadRepo.getParentsOfUnreadMessages(user2.id.get, uri1.id.get).size === 2
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = msg4.id.get, parentId = Some(msg3.id.get)))
          commentReadRepo.getParentsOfUnreadMessages(user2.id.get, uri1.id.get).size === 1

          val msg5 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google5", permissions = CommentPermissions.MESSAGE, parent = msg1.id))
          commentReadRepo.getParentsOfUnreadMessages(user1.id.get, uri1.id.get).size === 1

        }
      }
    }
    "update lastreadid when viewing thread" in {
      running(new EmptyApplication().withFakeSecureSocialUserService().withFakeHealthcheck().withFakeMail()) {

        import com.keepit.common.time._
        val (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3, msg4) = setup()

        db.readWrite { implicit s =>

          val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
            tokenType = None, expiresIn = None, refreshToken = None)
          val su = SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"),
            Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
          val sui = socialUserInfoRepo.save(SocialUserInfo(
            userId = user1.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
            credentials = Some(su)))
          val sui2 = socialUserInfoRepo.save(SocialUserInfo(
            userId = user2.id, fullName = "B 2", socialId = SocialId("222"), networkType = FACEBOOK,
            credentials = Some(su)))
          socialUserInfoRepo.get(sui.id.get) === sui
        }

        val commentReadRepo = inject[CommentReadRepo]

        val externalId = db.readOnly {implicit session =>
          val messages = commentReadRepo.getParentsOfUnreadMessages(user2.id.get, uri1.id.get)
          messages.size === 3
          messages.head.externalId
        }


        val fakeRequest = FakeRequest().withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook")
        val authRequest = AuthenticatedRequest(null, user1.id.get, fakeRequest)
        authRequest.session.get(SecureSocial.ProviderKey) === Some("facebook")
        val result = inject[ExtCommentController].getMessageThread(externalId)(authRequest)
        status(result) must equalTo(OK)

        db.readOnly {implicit session =>
          val messages = commentReadRepo.getParentsOfUnreadMessages(user1.id.get, uri1.id.get)
          messages.size === 1
        }
      }
    }

  }

}
