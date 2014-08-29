package com.keepit.eliza.model

import org.specs2.mutable.Specification
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.common.db.Id
import play.api.libs.json.JsNull
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._
import com.keepit.test.ElizaTestInjector
import org.joda.time.{ DateTime, Days }

class UserThreadRepoTest extends Specification with ElizaTestInjector {

  val modules = Seq(
    ElizaCacheModule(),
    FakeShoeboxServiceModule()
  )

  def setup() = {
    val thread1 = UserThread(
      user = Id[User](42),
      threadId = Id[MessageThread](1),
      uriId = Some(Id[NormalizedURI](1)),
      lastSeen = None,
      lastMsgFromOther = None,
      lastNotification = JsNull,
      lastActive = Some(currentDateTime)
    )
    val thread2 = UserThread(
      user = Id[User](42),
      threadId = Id[MessageThread](2),
      uriId = Some(Id[NormalizedURI](2)),
      lastSeen = None,
      lastMsgFromOther = None,
      lastNotification = JsNull,
      lastActive = Some(currentDateTime)
    )
    val thread3 = UserThread(
      user = Id[User](43),
      threadId = Id[MessageThread](3),
      uriId = Some(Id[NormalizedURI](3)),
      lastSeen = None,
      lastMsgFromOther = None,
      lastNotification = JsNull,
      lastActive = Some(currentDateTime)
    )
    Seq(thread1, thread2, thread3)
  }

  "UserThreadRepo" should {
    "get stats" in {
      withDb(modules: _*) { implicit injector =>
        val userThreadRepo = inject[UserThreadRepo]
        val clock = inject[Clock]
        val messageThreadRepo = inject[MessageThreadRepo]
        val user1 = Id[User](1)
        val user2 = Id[User](2)
        db.readWrite { implicit s =>
          userThreadRepo.getUserThreadsForEmailing(clock.now()).size === 0
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
            threadId = thread1.id.get,
            uriId = None,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            unread = true,
            replyable = true
          ))
          userThreadRepo.count === 1
          println(userThreadRepo.all)
          val toMail = userThreadRepo.getUserThreadsForEmailing(clock.now().plusMinutes(16))
          toMail.size === 1
          toMail.head.id.get === thread1.id.get
        }
        db.readOnlyMaster { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(1, 0, 0)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
          userThreadRepo.getUserThreadsForEmailing(clock.now()).size === 1
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
            threadId = thread1.id.get,
            uriId = None,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            lastActive = Some(inject[Clock].now)
          ))
        }
        db.readOnlyMaster { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(2, 1, 0)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
          val toMail = userThreadRepo.getUserThreadsForEmailing(clock.now().plusMinutes(16))
          toMail.size === 1
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
            threadId = thread1.id.get,
            uriId = None,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            lastActive = Some(inject[Clock].now),
            started = true,
            unread = true,
            replyable = true
          ))
        }
        db.readOnlyMaster { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(3, 2, 1)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
          val toMail = userThreadRepo.getUserThreadsForEmailing(clock.now().plusMinutes(16))
          toMail.size === 2
        }
      }
    }

    "check batch threads" in {
      withDb(modules: _*) { implicit injector =>
        val userThreadRepo = inject[UserThreadRepo]
        val threads = setup()

        db.readWrite { implicit s =>
          userThreadRepo.save(threads(0))
          userThreadRepo.save(threads(1))
          userThreadRepo.save(threads(2))

          val uris = Seq(Id[NormalizedURI](1), Id[NormalizedURI](2), Id[NormalizedURI](3))
          val result = userThreadRepo.checkUrisDiscussed(Id[User](42), uris)
          result.size === 2
        }
      }
    }

  }
}
