package com.keepit.common.social

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import com.keepit.inject._
import com.keepit.common.db.Id
import com.keepit.common.db.LargeString._
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
import play.core.TestApplication
import scala.collection.mutable.Map
import com.keepit.model._
import java.sql.Connection
import com.keepit.common.db.ExternalId
import org.joda.time.DateTime
import com.keepit.common.db.Id

@RunWith(classOf[JUnitRunner])
class ThreadInfoTest extends SpecificationWithJUnit {

  def setup() = {
    CX.withConnection { implicit conn =>
      val user1 = User(firstName = "Andrew", lastName = "Conner").save
      val user2 = User(firstName = "Eishay", lastName = "Smith").save

      val uri1 = NormalizedURIFactory("Google", "http://www.google.com/").save
      val uri2 = NormalizedURIFactory("Bing", "http://www.bing.com/").save

      SocialUserInfo(fullName = "Andrew Conner", socialId = SocialId("asdf"), networkType = SocialNetworks.FACEBOOK, userId = user1.id).save
      SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("fdaa"), networkType = SocialNetworks.FACEBOOK, userId = user2.id).save

      // Messages
      val msg1 = Comment(uriId = uri1.id.get, userId = user1.id.get, pageTitle = uri1.title.get, text = "Conversation on Google1", permissions = CommentPermissions.MESSAGE).save
      CommentRecipient(commentId = msg1.id.get, userId = Some(user2.id.get)).save
      val msg2 = Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google2", permissions = CommentPermissions.MESSAGE, parent = msg1.id).save
      CommentRecipient(commentId = msg2.id.get, userId = Some(user1.id.get)).save
      val msg3 = Comment(uriId = uri1.id.get, userId = user2.id.get, pageTitle = uri1.title.get, text = "Conversation on Google3", permissions = CommentPermissions.MESSAGE, parent = msg1.id).save

      (user1, user2, msg1)
    }
  }


  "ThreadInfo" should {
    "load with initiator" in {
      running(new EmptyApplication()) {
        val (user1, user2, msg) = setup()
        val info = CX.withConnection { implicit conn => ThreadInfo(msg, user1.id) }
        info.recipients.size === 1
        info.recipients.head.externalId === user2.externalId
      }
    }
    "load with recepient" in {
      running(new EmptyApplication()) {
        val (user1, user2, msg) = setup()
        val info = CX.withConnection { implicit conn => ThreadInfo(msg, user2.id) }
        info.recipients.size === 1
        info.recipients.head.externalId === user1.externalId
      }
    }
  }
}

