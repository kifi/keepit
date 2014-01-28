package com.keepit.eliza.model

import org.specs2.mutable.Specification
import com.keepit.test.DbTestInjector
import com.keepit.model.User
import com.keepit.common.db.Id
import play.api.libs.json.JsNull
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._

class UserThreadRepoTest extends Specification with DbTestInjector {

  "UserThreadRepo" should {
    "get stats" in {
      withDb(ElizaCacheModule(), FakeShoeboxServiceModule()) { implicit injector =>
        val userThreadRepo = inject[UserThreadRepo]
        val messageThreadRepo = inject[MessageThreadRepo]
        val user1 = Id[User](1)
        val user2 = Id[User](2)
        db.readWrite { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(0, 0, 0)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
          val thread1 = messageThreadRepo.save(MessageThread(
            uriId = None,
            url = None,
            nUrl = None,
            pageTitle = None,
            participants = None,
            participantsHash = None,
            replyable = false
          ))
          userThreadRepo.save(UserThread(
            user = user1,
            thread = thread1.id.get,
            uriId = None,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull
          ))
        }
        db.readOnly {implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(1, 0, 0)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
        }
        db.readWrite { implicit s =>
          val thread1 = messageThreadRepo.save(MessageThread(
            uriId = None,
            url = None,
            nUrl = None,
            pageTitle = None,
            participants = None,
            participantsHash = None,
            replyable = false
          ))
          userThreadRepo.save(UserThread(
            user = user1,
            thread = thread1.id.get,
            uriId = None,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            lastActive = Some(inject[Clock].now)
          ))
        }
        db.readOnly { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(2, 1, 0)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
        }
        db.readWrite { implicit s =>
          val thread1 = messageThreadRepo.save(MessageThread(
            uriId = None,
            url = None,
            nUrl = None,
            pageTitle = None,
            participants = None,
            participantsHash = None,
            replyable = false
          ))
          userThreadRepo.save(UserThread(
            user = user1,
            thread = thread1.id.get,
            uriId = None,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            lastActive = Some(inject[Clock].now),
            started = true
          ))
        }
        db.readOnly { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(3, 2, 1)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
        }
      }
    }
  }
}
