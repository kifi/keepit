package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import com.keepit.test.EmptyApplication

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.LargeString._
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks

import securesocial.core._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class CommentReadTest extends SpecificationWithJUnit {

  def setup() = {
    inject[DBConnection].readWrite {implicit s =>
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

        inject[DBConnection].readWrite { implicit s =>
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

          commentReadRepo.getCommentRead(user1.id.get, uri1.id.get).isDefined == false

          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = Some(comment2.id.get)))

          commentReadRepo.hasUnreadComments(user1.id.get, uri1.id.get) === false
          commentReadRepo.hasUnreadComments(user2.id.get, uri1.id.get) === true
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = Some(comment2.id.get)))
          commentReadRepo.hasUnreadComments(user2.id.get, uri1.id.get) === false
        }
      }
    }
    "keep track of read/unread messages" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3, msg4) = setup()

        inject[DBConnection].readWrite { implicit s =>
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

          val messages = commentReadRepo.getUnreadMessages(user1.id.get, uri1.id.get)
          messages.size === 2
          println(commentReadRepo.getUnreadMessages(user1.id.get, uri1.id.get))
          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = Some(msg1.id.get), parentId = Some(msg1.id.get)))
          commentReadRepo.getUnreadMessages(user1.id.get, uri1.id.get).size === 1
          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = Some(msg2.id.get), parentId = Some(msg2.id.get)))
          commentReadRepo.getUnreadMessages(user1.id.get, uri1.id.get).size === 0

          commentReadRepo.getUnreadMessages(user2.id.get, uri1.id.get).size === 3
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = Some(messages(1).id.get), parentId = Some(messages(1).id.get)))
          commentReadRepo.getUnreadMessages(user2.id.get, uri1.id.get).size === 2
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = Some(msg4.id.get), parentId = Some(msg3.id.get)))
          commentReadRepo.getUnreadMessages(user2.id.get, uri1.id.get).size === 1

          val msg5 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google5", permissions = CommentPermissions.MESSAGE, parent = msg1.id))
          commentReadRepo.getUnreadMessages(user1.id.get, uri1.id.get).size === 1

        }
      }
    }
  }

}
