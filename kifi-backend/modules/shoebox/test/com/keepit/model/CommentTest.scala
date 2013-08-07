package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.LargeString._
import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.{ShoeboxTestInjector, ShoeboxInjectionHelpers, DeprecatedEmptyApplication}
import play.api.test.Helpers._
import com.google.inject.Injector

class CommentTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    inject[Database].readWrite {implicit s =>
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

      val uri1 = uriRepo.save(normalizedURIFactory.apply("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(normalizedURIFactory.apply("Bing", "http://www.bing.com/"))

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
      val msg2 = commentRepo.save(Comment(uriId = uri2.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Bing", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)))
      val msg3 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE))
      val msg4 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google4", permissions = CommentPermissions.MESSAGE, parent = msg3.id))

      (user1, user2, uri1, uri2, msg1, msg2, msg3)
    }
  }

  "Comment" should {
    "use caching for counts (with proper invalidation)" in {
      withDb() { implicit injector =>
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
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()

        inject[Database].readWrite { implicit s =>
          val user3 = userRepo.save(User(firstName = "Other", lastName = "User"))

          commentRepo.getLastPublicIdByConnection(user1.id.get, uri1.id.get).isEmpty === true
          commentRepo.getLastPublicIdByConnection(user2.id.get, uri1.id.get).isEmpty === true
          commentRepo.getLastPublicIdByConnection(user3.id.get, uri1.id.get).isEmpty === true
          userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user2.id.get))

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
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        inject[Database].readOnly {implicit s =>
          commentRepo.all().length === 9
        }
      }
    }
   "count" in {
     withDb() { implicit injector =>
        setup()
        inject[Database].readOnly {implicit s =>
          commentRepo.count(CommentPermissions.PUBLIC) === 3
          commentRepo.count(CommentPermissions.MESSAGE) === 4
        }
      }
    }
    "count and load public comments by URI" in {
      withDb() { implicit injector =>
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
      withDb() { implicit injector =>
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
      withDb() { implicit injector =>
        inject[CommentFormatter].toPlainText("[hi there](x-kifi-sel:body>foo.bar#there)") === "[hi there]"
        inject[CommentFormatter].toPlainText("A [hi there](x-kifi-sel:foo.bar#there) B") === "A [hi there] B"
        inject[CommentFormatter].toPlainText("(A) [hi there](x-kifi-sel:foo.bar#there:nth-child(2\\)>a:nth-child(1\\)) [B] C") === "(A) [hi there] [B] C"
      }
    }

    "give the parent id for a list of recipients" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, msg1, msg2, msg3) = setup()
        db.readWrite { implicit session =>
          val user3 = userRepo.save(User(firstName = "Bob", lastName = "Fred"))
          val user4 = userRepo.save(User(firstName = "Wilma", lastName = "Schoshlatski"))

          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user1.id.get, user4.id.get)) === None
          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user1.id.get, user2.id.get)) === msg1.id
          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user2.id.get, user1.id.get)) === msg1.id
          commentRepo.getParentByUriParticipants(uri2.id.get, Set(user1.id.get, user2.id.get)) === msg2.id

          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user1.id.get, user2.id.get, user3.id.get)) === None
          val msg4 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE))
          commentRecipientRepo.save(CommentRecipient(commentId = msg4.id.get, userId = Some(user1.id.get)))
          commentRecipientRepo.save(CommentRecipient(commentId = msg4.id.get, userId = Some(user3.id.get)))

          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user1.id.get, user2.id.get, user3.id.get)) === msg4.id

          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user1.id.get, user2.id.get)) === msg1.id
          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user3.id.get, user1.id.get)) === None
          commentRepo.getParentByUriParticipants(uri1.id.get, Set(user2.id.get, user3.id.get)) === None
        }
      }
    }
  }

}
