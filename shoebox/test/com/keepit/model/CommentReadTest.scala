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
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

      val uri1 = uriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))

      // Public
      val comment1 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "comment 1", permissions = CommentPermissions.PUBLIC))
      val comment2 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "comment 2", permissions = CommentPermissions.PUBLIC))
      val comment3 = commentRepo.save(Comment(uriId = uri2.id.get, userId = user1.id.get, pageTitle = uri2.title.get, text = "comment 3", permissions = CommentPermissions.PUBLIC))

      // Messages
      val msg1 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "message 1", permissions = CommentPermissions.MESSAGE))
      val msg2 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "message 2", permissions = CommentPermissions.MESSAGE))
      val msg3 = commentRepo.save(Comment(uriId = uri2.id.get, userId = user1.id.get, pageTitle = uri2.title.get, text = "message 3", permissions = CommentPermissions.MESSAGE))

      (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3)
    }
  }

  "CommentRead" should {
    "keep track of read/unread comments" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3) = setup()

        db.readWrite { implicit s =>
          commentReadRepo.getByUserAndUri(user1.id.get, uri1.id.get) must beNone
          commentReadRepo.getByUserAndUri(user2.id.get, uri1.id.get) must beNone
          commentReadRepo.getByUserAndUri(user1.id.get, uri2.id.get) must beNone
          commentReadRepo.getByUserAndUri(user2.id.get, uri2.id.get) must beNone

          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = comment2.id.get))
          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = comment2.id.get))

          commentReadRepo.getByUserAndUri(user1.id.get, uri1.id.get) must beSome
          commentReadRepo.getByUserAndUri(user2.id.get, uri1.id.get) must beSome
          commentReadRepo.getByUserAndUri(user1.id.get, uri2.id.get) must beNone
          commentReadRepo.getByUserAndUri(user2.id.get, uri2.id.get) must beNone
        }
      }
    }

    "keep track of read/unread messages" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, comment1, comment2, comment3, msg1, msg2, msg3) = setup()

        db.readWrite { implicit s =>
          commentReadRepo.getByUserAndParent(user1.id.get, msg1.id.get) must beNone
          commentReadRepo.getByUserAndParent(user2.id.get, msg1.id.get) must beNone

          commentReadRepo.save(CommentRead(userId = user1.id.get, uriId = uri1.id.get, lastReadId = msg1.id.get, parentId = Some(msg1.id.get)))
          commentReadRepo.getByUserAndParent(user1.id.get, msg1.id.get).get.lastReadId === msg1.id.get
          commentReadRepo.getByUserAndParent(user2.id.get, msg1.id.get) must beNone

          commentReadRepo.save(commentReadRepo.getByUserAndParent(user1.id.get, msg1.id.get).get.withLastReadId(msg2.id.get))
          commentReadRepo.getByUserAndParent(user1.id.get, msg1.id.get).get.lastReadId === msg2.id.get
          commentReadRepo.getByUserAndParent(user2.id.get, msg1.id.get) must beNone

          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri1.id.get, lastReadId = msg2.id.get, parentId = Some(msg1.id.get)))
          commentReadRepo.getByUserAndParent(user1.id.get, msg1.id.get).get.lastReadId === msg2.id.get
          commentReadRepo.getByUserAndParent(user2.id.get, msg1.id.get).get.lastReadId === msg2.id.get
          commentReadRepo.getByUserAndParent(user1.id.get, msg2.id.get) must beNone  // msg2 is not the parent
          commentReadRepo.getByUserAndParent(user2.id.get, msg2.id.get) must beNone  // msg2 is not the parent

          commentReadRepo.save(CommentRead(userId = user2.id.get, uriId = uri2.id.get, lastReadId = msg3.id.get, parentId = Some(msg3.id.get)))
          commentReadRepo.getByUserAndParent(user1.id.get, msg3.id.get) must beNone
          commentReadRepo.getByUserAndParent(user2.id.get, msg3.id.get).get.lastReadId === msg3.id.get
        }
      }
    }
  }

}
