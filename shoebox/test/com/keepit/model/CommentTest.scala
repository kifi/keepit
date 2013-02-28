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

class CommentTest extends SpecificationWithJUnit {

  def setup() = {
    inject[Database].readWrite {implicit s =>
      val commentRepo = inject[CommentRepo]
      val userRepo = inject[UserRepo]
      val commentRecipientRepo = inject[CommentRecipientRepo]
      val normalizedURIRepo = inject[NormalizedURIRepo]

      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

      val uri1 = normalizedURIRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = normalizedURIRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))

      // Public
      commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google1", permissions = CommentPermissions.PUBLIC))
      commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google2", permissions = CommentPermissions.PUBLIC))
      commentRepo.save(Comment(uriId = uri2.id.get, userId = user1.id.get, pageTitle = uri2.title.get, text = "Public Comment on Bing", permissions = CommentPermissions.PUBLIC))

      // Private
      commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Private Comment on Google1", permissions = CommentPermissions.PRIVATE))
      commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Private Comment on Google2", permissions = CommentPermissions.PRIVATE))

      // Messages
      val msg1 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google1", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg1.id.get, userId = Some(user2.id.get)))
      val msg2 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)))
      val msg3 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE))
      val msg4 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google4", permissions = CommentPermissions.MESSAGE, parent = msg3.id))

      (user1, user2, uri1, uri2, msg3)
    }
  }

  "Comment" should {
    "use caching for counts (with proper invalidation)" in {
      running(new EmptyApplication()) {

        val commentRepo = inject[CommentRepoImpl]
        val commentRecipientRepo = inject[CommentRecipientRepo]

        commentRepo.commentCountCache.get(CommentCountUriIdKey(Id[NormalizedURI](1))).isDefined === false
        commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(Id[NormalizedURI](1), Id[User](1))).isDefined === false

        val (user1, user2, uri1, uri2, msg3) = setup()

        inject[Database].readOnly { implicit s =>
          commentRepo.getPublicCount(Id[NormalizedURI](1))
          commentRepo.getMessagesWithChildrenCount(Id[NormalizedURI](1), Id[User](1))
          commentRepo.getMessagesWithChildrenCount(Id[NormalizedURI](2), Id[User](1))
        }

        commentRepo.commentCountCache.get(CommentCountUriIdKey(Id[NormalizedURI](1))).get === 2
        commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(Id[NormalizedURI](1), Id[User](1))).get === 2
        commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(Id[NormalizedURI](2), Id[User](1))).get === 0

        // Caching invalidation
        inject[Database].readWrite { implicit s =>
          commentRecipientRepo.save(CommentRecipient(commentId = msg3.id.get, userId = Some(user1.id.get)))
          commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "New message on msg3 to user2!", permissions = CommentPermissions.MESSAGE, parent = msg3.id))
          commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(uri1.id.get, user1.id.get)).isDefined === false
          commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(uri1.id.get, user2.id.get)).isDefined === false
          commentRepo.getMessagesWithChildrenCount(uri1.id.get, user1.id.get)
          commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(uri1.id.get, user1.id.get)).isDefined === true
          commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(uri1.id.get, user2.id.get)).isDefined === false
          commentRepo.getMessagesWithChildrenCount(uri1.id.get, user2.id.get)
          commentRepo.messageWithChildrenCountCache.get(MessageWithChildrenCountUriIdUserIdKey(uri1.id.get, user2.id.get)).isDefined === true
        }

      }
    }
    "give all comments by friends" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()

        inject[Database].readWrite { implicit s =>
          val userRepo = inject[UserRepo]
          val user3 = userRepo.save(User(firstName = "Other", lastName = "User"))

          val commentRepo = inject[CommentRepo]
          commentRepo.getLastPublicIdByConnection(user1.id.get, uri1.id.get).isEmpty === true
          commentRepo.getLastPublicIdByConnection(user2.id.get, uri1.id.get).isEmpty === true
          commentRepo.getLastPublicIdByConnection(user3.id.get, uri1.id.get).isEmpty === true


          val socialUserInfoRepo = inject[SocialUserInfoRepo]
          val socialUser1 = socialUserInfoRepo.save(SocialUserInfo(userId = user1.id, fullName = "Andrew Conner", socialId = SocialId("1111111"), networkType = SocialNetworks.FACEBOOK))
          val socialUser2 = socialUserInfoRepo.save(SocialUserInfo(userId = user2.id, fullName = "Eishay Smith", socialId = SocialId("2222222"), networkType = SocialNetworks.FACEBOOK))
          val socialConnRepo = inject[SocialConnectionRepo]
          socialConnRepo.save(SocialConnection(socialUser1 = socialUser1.id.get, socialUser2 = socialUser2.id.get))

          commentRepo.getLastPublicIdByConnection(user1.id.get, uri1.id.get).size === 1
          commentRepo.getLastPublicIdByConnection(user2.id.get, uri1.id.get).size === 1
          commentRepo.getLastPublicIdByConnection(user1.id.get, uri2.id.get).size === 0
          commentRepo.getLastPublicIdByConnection(user2.id.get, uri2.id.get).size === 1

          commentRepo.save(Comment(uriId = uri2.id.get, userId = user2.id.get, pageTitle = uri2.title.get, text = "New comment on Google1", permissions = CommentPermissions.PUBLIC))
          commentRepo.getLastPublicIdByConnection(user1.id.get, uri2.id.get).size === 1
        }
      }
    }
    "add comments" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          inject[CommentRepo].all().length === 9
        }
      }
    }
   "count" in {
      running(new EmptyApplication()) {
        setup()
        inject[Database].readOnly {implicit s =>
          inject[CommentRepo].count(CommentPermissions.PUBLIC) === 3
          inject[CommentRepo].count(CommentPermissions.MESSAGE) === 4
        }
      }
    }
    "count and load public comments by URI" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          inject[CommentRepo].getPublicCount(uri1.id.get) === 2
          inject[CommentRepo].getPublicCount(uri2.id.get) === 1
          inject[CommentRepo].getPublic(uri1.id.get).length === 2
          inject[CommentRepo].getPublic(uri2.id.get).length === 1
        }
      }
    }
    "count and load messages by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          val repo = inject[CommentRepo]
          repo.getMessages(uri1.id.get, user1.id.get).length === 2
          repo.getMessages(uri1.id.get, user2.id.get).length === 3
          repo.getMessages(uri2.id.get, user1.id.get).length === 0
          repo.getMessages(uri2.id.get, user2.id.get).length === 0
        }
      }
    }
    "count messages AND comments by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          val repo = inject[CommentRepo]
          repo.getChildCount(msg3.id.get) === 1
          repo.getMessagesWithChildrenCount(uri1.id.get, user1.id.get) === 2
          repo.getMessagesWithChildrenCount(uri1.id.get, user2.id.get) === 4
          repo.getMessagesWithChildrenCount(uri2.id.get, user1.id.get) === 0
          repo.getMessagesWithChildrenCount(uri2.id.get, user2.id.get) === 0
        }
      }
    }
  }

}
