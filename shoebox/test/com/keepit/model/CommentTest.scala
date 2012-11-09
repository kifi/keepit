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
      Comment(normalizedURI = uri1.id.get, userId = user1.id.get, text = "Public Comment on Google1", permissions = Comment.Permissions.PUBLIC).save
      Comment(normalizedURI = uri1.id.get, userId = user2.id.get, text = "Public Comment on Google2", permissions = Comment.Permissions.PUBLIC).save
      Comment(normalizedURI = uri2.id.get, userId = user1.id.get, text = "Public Comment on Bing", permissions = Comment.Permissions.PUBLIC).save
  
      // Private
      Comment(normalizedURI = uri1.id.get, userId = user1.id.get, text = "Private Comment on Google1", permissions = Comment.Permissions.PRIVATE).save
      Comment(normalizedURI = uri1.id.get, userId = user1.id.get, text = "Private Comment on Google2", permissions = Comment.Permissions.PRIVATE).save
      
      // Conversation
      val convo = Comment(normalizedURI = uri1.id.get, userId = user1.id.get, text = "Conversation on Google1", permissions = Comment.Permissions.CONVERSATION).save
      CommentRecipient(commentId = convo.id.get, userId = Some(user2.id.get)).save
      val convo2 = Comment(normalizedURI = uri1.id.get, userId = user2.id.get, text = "Conversation on Google2", permissions = Comment.Permissions.CONVERSATION).save
      CommentRecipient(commentId = convo2.id.get, userId = Some(user1.id.get)).save
      val convo3 = Comment(normalizedURI = uri1.id.get, userId = user2.id.get, text = "Conversation on Google3", permissions = Comment.Permissions.CONVERSATION).save
  
                
      (user1, user2, uri1, uri2)
    }
  }
  "Comment" should {
    "add comments" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>          
          Comment.all.length === 8
        }
      }
    }
    "show public comments by URI" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>          
          val uri1PublicComments = Comment.getPublicByNormalizedUri(uri1.id.get)
          val uri2PublicComments = Comment.getPublicByNormalizedUri(uri2.id.get)
          uri1PublicComments.length === 2
        }
      }
    }
    "show private comments by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          val uri1PrivateComments1 = Comment.getPrivateByNormalizedUri(uri1.id.get, user1.id.get)
          val uri1PrivateComments2 = Comment.getPrivateByNormalizedUri(uri1.id.get, user2.id.get)
          val uri2PrivateComments1 = Comment.getPrivateByNormalizedUri(uri2.id.get, user1.id.get)
          val uri2PrivateComments2 = Comment.getPrivateByNormalizedUri(uri2.id.get, user2.id.get)
          
          uri1PrivateComments1.length === 2
          uri1PrivateComments2.length === 0
          uri2PrivateComments1.length === 0
          uri1PrivateComments2.length === 0
          
        }
      }
    }
    "show conversations by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>          
          val uri1Conversation1 = Comment.getConversationsByNormalizedUri(uri1.id.get, user1.id.get)
          val uri1Conversation2 = Comment.getConversationsByNormalizedUri(uri1.id.get, user2.id.get)
          val uri2Conversation1 = Comment.getConversationsByNormalizedUri(uri2.id.get, user1.id.get)

          uri1Conversation1.length === 2
          uri1Conversation2.length === 3
          uri2Conversation1.length === 0
          
        }
      }
    }
  }
  
}