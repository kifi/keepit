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
    CX.withConnection { implicit conn =>
      val user1 = User(firstName = "Andrew", lastName = "Conner").save
      val user2 = User(firstName = "Eishay", lastName = "Smith").save

      val uri1 = NormalizedURIFactory("Google", "http://www.google.com/").save
      val uri2 = NormalizedURIFactory("Bing", "http://www.bing.com/").save

      // Public
      Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google1", permissions = CommentPermissions.PUBLIC).save
      Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google2", permissions = CommentPermissions.PUBLIC).save
      Comment(uriId = uri2.id.get, userId = user1.id.get, pageTitle = uri2.title.get, text = "Public Comment on Bing", permissions = CommentPermissions.PUBLIC).save

      // Private
      Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Private Comment on Google1", permissions = CommentPermissions.PRIVATE).save
      Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Private Comment on Google2", permissions = CommentPermissions.PRIVATE).save

      // Messages
      val msg1 = Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google1", permissions = CommentPermissions.MESSAGE).save
      CommentRecipient(commentId = msg1.id.get, userId = Some(user2.id.get)).save
      val msg2 = Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = CommentPermissions.MESSAGE).save
      CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)).save
      val msg3 = Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE).save
      val msg4 = Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google4", permissions = CommentPermissions.MESSAGE, parent = msg3.id).save

      (user1, user2, uri1, uri2, msg3)
    }
  }

  "Comment" should {
    "add comments" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        inject[DBConnection].readOnly {implicit s =>
          inject[CommentRepo].all.length === 9
        }
      }
    }
   "count" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit conn =>
          CommentCxRepo.count(CommentPermissions.PUBLIC) === 3
          CommentCxRepo.count(CommentPermissions.MESSAGE) === 4
        }
      }
    }
    "count and load public comments by URI" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          CommentCxRepo.getPublicCount(uri1.id.get) === 2
          CommentCxRepo.getPublicCount(uri2.id.get) === 1
          CommentCxRepo.getPublic(uri1.id.get).length === 2
          CommentCxRepo.getPublic(uri2.id.get).length === 1
        }
      }
    }
    "count and load private comments by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          CommentCxRepo.getPrivateCount(uri1.id.get, user1.id.get) === 2
          CommentCxRepo.getPrivateCount(uri1.id.get, user2.id.get) === 0
          CommentCxRepo.getPrivateCount(uri2.id.get, user1.id.get) === 0
          CommentCxRepo.getPrivateCount(uri2.id.get, user2.id.get) === 0
          CommentCxRepo.getPrivate(uri1.id.get, user1.id.get).length === 2
          CommentCxRepo.getPrivate(uri1.id.get, user2.id.get).length === 0
          CommentCxRepo.getPrivate(uri2.id.get, user1.id.get).length === 0
          CommentCxRepo.getPrivate(uri2.id.get, user2.id.get).length === 0
        }
      }
    }
    "count and load messages by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          CommentCxRepo.getMessageCount(uri1.id.get, user1.id.get) === 2
          CommentCxRepo.getMessageCount(uri1.id.get, user2.id.get) === 3
          CommentCxRepo.getMessageCount(uri2.id.get, user1.id.get) === 0
          CommentCxRepo.getMessageCount(uri2.id.get, user2.id.get) === 0
          CommentCxRepo.getMessages(uri1.id.get, user1.id.get).length === 2
          CommentCxRepo.getMessages(uri1.id.get, user2.id.get).length === 3
          CommentCxRepo.getMessages(uri2.id.get, user1.id.get).length === 0
          CommentCxRepo.getMessages(uri2.id.get, user2.id.get).length === 0
        }
      }
    }
    "count messages AND comments by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          CommentCxRepo.getChildCount(msg3.id.get) === 1
          CommentCxRepo.getMessagesWithChildrenCount(uri1.id.get, user1.id.get) === 2
          CommentCxRepo.getMessagesWithChildrenCount(uri1.id.get, user2.id.get) === 4
          CommentCxRepo.getMessagesWithChildrenCount(uri2.id.get, user1.id.get) === 0
          CommentCxRepo.getMessagesWithChildrenCount(uri2.id.get, user2.id.get) === 0
        }
      }
    }
  }

}
