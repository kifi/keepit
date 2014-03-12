package com.keepit.commanders.emails

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model._
import com.keepit.heimdal.HeimdalContext
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.scraper.FakeScrapeSchedulerModule
import scala.Some
import com.keepit.normalizer.NormalizationService
import com.keepit.search.FakeSearchServiceClientModule
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.eliza.model.ThreadItem
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.DeepLocator
import com.keepit.common.mail.{FakeOutbox, FakeMailModule}

class EmailNotificationsCommanderTest extends Specification with ShoeboxTestInjector {

  "EmailNotificationsCommander" should {

    "remove tags and tags to keeps" in {
      withDb(FakeMailModule()) { implicit injector =>
        val userRepo = inject[UserRepo]
        val deepLinkRepo = inject[DeepLinkRepo]
        val (link, william, abraham, george) = inject[Database].readWrite { implicit session =>
          val william = userRepo.save(User(firstName = "William", lastName = "Shakespeare"))
          val george = userRepo.save(User(firstName = "George", lastName = "Washington"))
          val link = DeepLink(initiatorUserId = william.id, recipientUserId = george.id,
            uriId = None, deepLocator = DeepLocator("/foo/bar"))
          (deepLinkRepo.save(link),
          william,
          userRepo.save(User(firstName = "Abraham", lastName = "Lincoln")),
          george)
        }

        val commander = inject[EmailNotificationsCommander]
        val title = "foo bar"
        val threadItems: Seq[ThreadItem] = ThreadItem(william.id.get, "yo man") :: ThreadItem(george.id.get, "what's going on dood?") :: ThreadItem(william.id.get, "we're cool") :: Nil
        val otherParticipantIds: Seq[Id[User]] = william.id.get :: george.id.get :: abraham.id.get :: Nil
        val recipientUserId: Id[User] = george.id.get
        val deepLocator: DeepLocator = link.deepLocator

        commander.sendUnreadMessages(threadItems, otherParticipantIds, recipientUserId, title, deepLocator)

        val outbox = inject[FakeOutbox]
        println(outbox.head.htmlBody)
        outbox.head.htmlBody.contains(s"https://www.kifi.com/users/${william.externalId}/pics/112/0.jpg") === true
        outbox.head.htmlBody.contains(s"https://www.kifi.com/users/${george.externalId}/pics/112/0.jpg") === true
        outbox.head.htmlBody.contains(s"https://www.kifi.com/users/${abraham.externalId}/pics/112/0.jpg") === false
        outbox.head.htmlBody.contains(s"""<a href="https://www.kifi.com/r/${deepLocator.value}" """) === false
        outbox.size === 1

      }

    }
  }
}