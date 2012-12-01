package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
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

      val uri1 = NormalizedURI("Google", "http://www.google.com/").save
      val uri2 = NormalizedURI("Bing", "http://www.bing.com/").save

      // Public
      Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google1", permissions = Comment.Permissions.PUBLIC).save
      Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Public Comment on Google2", permissions = Comment.Permissions.PUBLIC).save
      Comment(uriId = uri2.id.get, userId = user1.id.get, pageTitle = uri2.title.get, text = "Public Comment on Bing", permissions = Comment.Permissions.PUBLIC).save

      // Private
      Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Private Comment on Google1", permissions = Comment.Permissions.PRIVATE).save
      Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Private Comment on Google2", permissions = Comment.Permissions.PRIVATE).save

      // Messages
      val msg1 = Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google1", permissions = Comment.Permissions.MESSAGE).save
      CommentRecipient(commentId = msg1.id.get, userId = Some(user2.id.get)).save
      val msg2 = Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = Comment.Permissions.MESSAGE).save
      CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)).save
      val msg3 = Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = Comment.Permissions.MESSAGE).save
      val msg4 = Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google4", permissions = Comment.Permissions.MESSAGE, parent = msg3.id).save

      (user1, user2, uri1, uri2, msg3)
    }
  }

  "Comment" should {
    "add comments" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          Comment.all.length === 9
        }
      }
    }
    "count and load public comments by URI" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          Comment.getPublicCount(uri1.id.get) === 2
          Comment.getPublicCount(uri2.id.get) === 1
          Comment.getPublic(uri1.id.get).length === 2
          Comment.getPublic(uri2.id.get).length === 1
        }
      }
    }
    "count and load private comments by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          Comment.getPrivateCount(uri1.id.get, user1.id.get) === 2
          Comment.getPrivateCount(uri1.id.get, user2.id.get) === 0
          Comment.getPrivateCount(uri2.id.get, user1.id.get) === 0
          Comment.getPrivateCount(uri2.id.get, user2.id.get) === 0
          Comment.getPrivate(uri1.id.get, user1.id.get).length === 2
          Comment.getPrivate(uri1.id.get, user2.id.get).length === 0
          Comment.getPrivate(uri2.id.get, user1.id.get).length === 0
          Comment.getPrivate(uri2.id.get, user2.id.get).length === 0
        }
      }
    }
    "count and load messages by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          Comment.getMessageCount(uri1.id.get, user1.id.get) === 2
          Comment.getMessageCount(uri1.id.get, user2.id.get) === 3
          Comment.getMessageCount(uri2.id.get, user1.id.get) === 0
          Comment.getMessageCount(uri2.id.get, user2.id.get) === 0
          Comment.getMessages(uri1.id.get, user1.id.get).length === 2
          Comment.getMessages(uri1.id.get, user2.id.get).length === 3
          Comment.getMessages(uri2.id.get, user1.id.get).length === 0
          Comment.getMessages(uri2.id.get, user2.id.get).length === 0
        }
      }
    }
    "count messages AND comments by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg3) = setup()
        CX.withConnection { implicit conn =>
          Comment.getChildCount(msg3.id.get) === 1
          Comment.getMessagesWithChildrenCount(uri1.id.get, user1.id.get) === 2
          Comment.getMessagesWithChildrenCount(uri1.id.get, user2.id.get) === 4
          Comment.getMessagesWithChildrenCount(uri2.id.get, user1.id.get) === 0
          Comment.getMessagesWithChildrenCount(uri2.id.get, user2.id.get) === 0
        }
      }
    }
  }

}