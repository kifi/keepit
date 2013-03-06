package com.keepit.common.social

import org.specs2.mutable._
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.test.EmptyApplication
import play.core.TestApplication
import scala.collection.mutable.Map
import com.keepit.model._
import java.sql.Connection
import com.keepit.common.db.ExternalId
import org.joda.time.DateTime
import com.keepit.common.db.Id

class ThreadInfoTest extends Specification {

  def setup() = {
    inject[Database].readWrite { implicit session =>
      val commentRepo = inject[CommentRepo]
      val userRepo = inject[UserRepo]
      val commentRecipientRepo = inject[CommentRecipientRepo]
      val normalizedURIRepo = inject[NormalizedURIRepo]
      val socialuserInfoRepo = inject[SocialUserInfoRepo]

      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

      val uri1 = normalizedURIRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = normalizedURIRepo.save(NormalizedURIFactory("Bing", "http://www.bing.com/"))

      socialuserInfoRepo.save(SocialUserInfo(fullName = "Andrew Conner", socialId = SocialId("asdf"), networkType = SocialNetworks.FACEBOOK, userId = user1.id))
      socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("fdaa"), networkType = SocialNetworks.FACEBOOK, userId = user2.id))

      // Messages
      val msg1 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google1", permissions = CommentPermissions.MESSAGE))
      commentRecipientRepo.save(CommentRecipient(commentId = msg1.id.get, userId = Some(user2.id.get)))
      val msg2 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = CommentPermissions.MESSAGE, parent = msg1.id))
      commentRecipientRepo.save(CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)))
      val msg3 = commentRepo.save(Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE, parent = msg1.id))

      (user1, user2, msg1)
    }
  }


  "ThreadInfo" should {
    "load with initiator" in {
      running(new EmptyApplication()) {
        val (user1, user2, msg) = setup()
        val info = inject[Database].readOnly { implicit session => inject[ThreadInfoRepo].load(msg, user1.id) }
        info.recipients.size === 1
        info.recipients.head.externalId === user2.externalId
      }
    }
    "load with recepient" in {
      running(new EmptyApplication()) {
        val (user1, user2, msg) = setup()
        val info = inject[Database].readOnly { implicit session => inject[ThreadInfoRepo].load(msg, user2.id) }
        info.recipients.size === 1
        info.recipients.head.externalId === user1.externalId
      }
    }
  }
}

