package com.keepit.model

import org.specs2.mutable._
import com.keepit.test.{DbRepos, EmptyApplication}

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

class CommentTest extends Specification with DbRepos {

  def setup() = {
    inject[Database].readWrite {implicit s =>
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

      val uri1 = uriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))

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
      val msg2 = commentRepo.save(Comment(uriId = uri2.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)))
      val msg3 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE))
      val msg4 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google4", permissions = CommentPermissions.MESSAGE, parent = msg3.id))

      (user1, user2, uri1, uri2, msg1, msg2, msg3)
    }
  }

  "Comment" should {
    "use caching for counts (with proper invalidation)" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        val repo = commentRepo.asInstanceOf[CommentRepoImpl]

        repo.commentCountCache.get(CommentCountUriIdKey(uri1.id.get)) must beNone

        inject[Database].readOnly { implicit s => repo.getPublicCount(uri1.id.get) } === 2

        repo.commentCountCache.get(CommentCountUriIdKey(uri1.id.get)) === Some(2)

        // Caching invalidation
        inject[Database].readWrite { implicit s =>
          repo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "New comment!", permissions = CommentPermissions.PUBLIC))
        }

        repo.commentCountCache.get(CommentCountUriIdKey(uri1.id.get)) must beNone
      }
    }
    "give all comments by friends" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()

        inject[Database].readWrite { implicit s =>
          val user3 = userRepo.save(User(firstName = "Other", lastName = "User"))

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
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          commentRepo.all().length === 9
        }
      }
    }
   "count" in {
      running(new EmptyApplication()) {
        setup()
        inject[Database].readOnly {implicit s =>
          commentRepo.count(CommentPermissions.PUBLIC) === 3
          commentRepo.count(CommentPermissions.MESSAGE) === 4
        }
      }
    }
    "count and load public comments by URI" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          commentRepo.getPublicCount(uri1.id.get) === 2
          commentRepo.getPublicCount(uri2.id.get) === 1
          commentRepo.getPublic(uri1.id.get).length === 2
          commentRepo.getPublic(uri2.id.get).length === 1
        }
      }
    }
    "count and load messages by URI and UserId" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          val repo = commentRepo
          repo.getParentMessages(uri1.id.get, user1.id.get).length === 1
          repo.getParentMessages(uri1.id.get, user2.id.get).length === 2
          repo.getParentMessages(uri2.id.get, user1.id.get).length === 1
          repo.getParentMessages(uri2.id.get, user2.id.get).length === 1
        }
      }
    }

    "format comment text markdown as plain text" in {
      running(new EmptyApplication()) {  // TODO: no need for a Play application
        inject[CommentFormatter].toPlainText("[hi there](x-kifi-sel:body>foo.bar#there)") === "[hi there]"
        inject[CommentFormatter].toPlainText("A [hi there](x-kifi-sel:foo.bar#there) B") === "A [hi there] B"
        inject[CommentFormatter].toPlainText("(A) [hi there](x-kifi-sel:foo.bar#there:nth-child(2\\)>a:nth-child(1\\)) [B] C") === "(A) [hi there] [B] C"
      }
    }
    
    "give the parent id for a list of recipients" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        db.readWrite { implicit session =>
          val user3 = userRepo.save(User(firstName = "Bob", lastName = "Fred"))
          val user4 = userRepo.save(User(firstName = "Wilma", lastName = "Schoshlatski"))
          
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user1.id.get, user4.id.get)) === None
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user1.id.get, user2.id.get)) === msg1.id
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user2.id.get, user1.id.get)) === msg1.id
          commentRepo.getParentByUriRecipients(uri2.id.get, Set(user1.id.get, user2.id.get)) === msg2.id
          
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user1.id.get, user2.id.get, user3.id.get)) === None
          val msg4 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE))
          commentRecipientRepo.save(CommentRecipient(commentId = msg4.id.get, userId = Some(user1.id.get)))
          commentRecipientRepo.save(CommentRecipient(commentId = msg4.id.get, userId = Some(user3.id.get)))

          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user1.id.get, user2.id.get, user3.id.get)) === msg4.id
          
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user1.id.get, user2.id.get)) === msg1.id
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user3.id.get, user1.id.get)) === None
          commentRepo.getParentByUriRecipients(uri1.id.get, Set(user2.id.get, user3.id.get)) === None
        }
      }
    }
  }

}
