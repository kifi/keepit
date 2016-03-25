package com.keepit.eliza.notify

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ User, _ }
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.LibraryNewKeep
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike

class NotificationGroupingTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with ElizaInjectionHelpers {
  val modules = List(
    FakeElizaStoreModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule())

  "Notifications" should {
    "group properly" in {
      withDb(modules: _*) { implicit injector =>
        val (user, rando) = (Id[User](1), Id[User](134))
        val lib = Id[Library](42)
        val keepId = new AtomicLong()
        val now = inject[Clock].now

        def keep(keptAt: DateTime) = LibraryNewKeep(
          recipient = Recipient.fromUser(user),
          time = now,
          keptAt = keptAt,
          keeperId = Some(rando),
          keepId = Id[Keep](keepId.incrementAndGet()),
          libraryId = lib
        )

        val lotsOfOldKeepsCloseTogether = (1 to 10).map { x => keep(now.minusYears(2).plusMinutes(x)) }
        val lotsOfOldKeepsFarApart = (1 to 10).map { x => keep(now.minusYears(1).plusDays(x)) }
        val lotsOfRecentKeepsFarApart = (1 to 10).map { x => keep(now.minusHours(12).plusHours(x)) }
        val lotsOfRecentKeepsCloseTogether = (1 to 10).map { x => keep(now.minusMinutes(5).plusSeconds(x)) }

        notifCommander.processNewEvents(lotsOfOldKeepsCloseTogether).length === 1
        notifCommander.processNewEvents(lotsOfOldKeepsCloseTogether).length === 1
        notifCommander.processNewEvents(lotsOfOldKeepsFarApart).length === 1
        notifCommander.processNewEvents(lotsOfRecentKeepsFarApart).length === 1
        notifCommander.processNewEvents(lotsOfRecentKeepsCloseTogether).length === 10
      }
    }
  }
}
