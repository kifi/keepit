package com.keepit.eliza.model

import org.specs2.mutable.Specification
import com.keepit.model.{ Keep, MessageThreadFactory, NormalizedURI, User }
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

  "UserThreadRepo" should {
    "intern user threads by (userId, keepId)" in {
      withDb(modules: _*) { implicit injector =>
        val users = Seq.range(1, 3).map(Id[User](_))
        val keep = Id[Keep](42)
        val mt = db.readWrite { implicit s =>
          val mt = MessageThreadFactory.thread().withKeep(keep).withOnlyStarter(users.head).withUsers(users: _*).saved
          users.foreach { user => userThreadRepo.intern(UserThread.forMessageThread(mt)(user)) }
          mt
        }

        // 3 uts, all alive
        db.readOnlyMaster { implicit s =>
          userThreadRepo.aTonOfRecords must haveSize(users.length)
          userThreadRepo.getByKeep(keep) must haveSize(users.length)
        }
        // kill one of them
        db.readWrite { implicit s => userThreadRepo.deactivate(userThreadRepo.getUserThread(users.last, keep).get) }
        // 3 uts, 2 alive
        db.readOnlyMaster { implicit s =>
          userThreadRepo.aTonOfRecords must haveSize(users.length)
          userThreadRepo.getByKeep(keep) must haveSize(users.length - 1)
        }
        // intern new thread, it snakes the old thread's id
        db.readWrite { implicit s => userThreadRepo.intern(UserThread.forMessageThread(mt)(users.last)) }
        // 3 uts, 3 alive
        db.readOnlyMaster { implicit s =>
          userThreadRepo.aTonOfRecords must haveSize(users.length)
          userThreadRepo.getByKeep(keep) must haveSize(users.length)
        }
        1 === 1
      }
    }
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
          val thread1 = MessageThreadFactory.thread().saved
          val ut1 = userThreadRepo.save(UserThread.forMessageThread(thread1)(user1))
          userThreadRepo.markUnread(user1, thread1.keepId)
          userThreadRepo.count === 1
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
          val thread1 = MessageThreadFactory.thread().saved
          userThreadRepo.save(UserThread.forMessageThread(thread1)(user1).copy(lastActive = Some(inject[Clock].now), unread = false))
        }
        db.readOnlyMaster { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(2, 1, 0)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
          val toMail = userThreadRepo.getUserThreadsForEmailing(clock.now().plusMinutes(16))
          toMail.size === 1
        }
        db.readWrite { implicit s =>
          val thread1 = MessageThreadFactory.thread().withOnlyStarter(user1).saved
          userThreadRepo.save(UserThread.forMessageThread(thread1)(user1).copy(lastActive = Some(inject[Clock].now), unread = true))
        }
        db.readOnlyMaster { implicit s =>
          userThreadRepo.getUserStats(user1) === UserThreadStats(3, 2, 1)
          userThreadRepo.getUserStats(user2) === UserThreadStats(0, 0, 0)
          val toMail = userThreadRepo.getUserThreadsForEmailing(clock.now().plusMinutes(16))
          toMail.size === 2
        }
      }
    }
  }
}
