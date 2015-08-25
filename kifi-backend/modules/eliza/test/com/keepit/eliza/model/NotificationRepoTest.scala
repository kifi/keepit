package com.keepit.eliza.model

import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.NewSocialConnection
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaTestInjector, TestInjector }
import com.keepit.common.time._
import org.specs2.mutable.Specification

class NotificationRepoTest extends Specification with ElizaTestInjector {

  val modules = Seq(
    ElizaCacheModule()
  )

  "NotificationRepo" should {
    "persist notifications correctly" in {
      withDb(modules: _*) { implicit injector =>
        val notificationRepo = inject[NotificationRepo]
        val notif = db.readWrite { implicit session =>
          notificationRepo.save(Notification(
            recipient = Recipient(Id[User](3)),
            kind = NewSocialConnection,
            lastEvent = currentDateTime
          ))
        }
        notif.kind === NewSocialConnection

        val notif2 = db.readWrite { implicit session =>
          notificationRepo.save(Notification(
            recipient = Recipient(Id[User](4)),
            kind = NewSocialConnection,
            lastEvent = currentDateTime
          ))
        }
        notif2.kind === NewSocialConnection
      }
    }
  }

}
