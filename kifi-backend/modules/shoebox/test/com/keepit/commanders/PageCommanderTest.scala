package com.keepit.commanders

import com.keepit.classify.DomainRepo
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.template.{ EmailTip, EmailTrackingParam }
import com.keepit.common.mail.{ ElectronicMail, ElectronicMailRepo, EmailAddress, SystemEmailAddress }
import com.keepit.common.social.{ BasicUserRepo, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time.zones
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.{ SearchServiceClient, FakeSearchServiceClientModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class PageCommanderTest extends Specification with ShoeboxTestInjector {

  def modules = FakeKeepImportsModule() ::
    FakeExecutionContextModule() ::
    FakeShoeboxStoreModule() ::
    FakeSearchServiceClientModule() ::
    FakeCortexServiceClientModule() ::
    FakeScrapeSchedulerModule() ::
    FakeShoeboxServiceModule() ::
    FakeUserActionsModule() ::
    FakeCuratorServiceClientModule() ::
    FakeABookServiceClientModule() ::
    FakeSocialGraphModule() ::
    FakeSliderHistoryTrackerModule() ::
    Nil

  "PageCommander" should {

    "firstQualityFilter" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PageCommander]
        val lib1 = library().withId(1).withName("foo").withKind(LibraryKind.SYSTEM_PERSONA).withMemberCount(10).get
        val lib2 = library().withId(2).withName("bar").withMemberCount(10).get
        val lib3 = library().withId(3).withName("test my lib").get
        val lib4 = library().withId(4).withName("legit").withMemberCount(20).get
        val lib5 = library().withId(5).withName("this is Pocket imports").get
        val lib6 = library().withId(6).withName("Bookmark").get
        val lib7 = library().withId(7).withName("Instapaper").get
        val filtered = commander.firstQualityFilterAndSort(Seq(lib1, lib2, lib3, lib4, lib5, lib6, lib7))
        filtered.map(_.id.get) === Seq(lib4, lib2, lib1).map(_.id.get)
      }
    }

    "secondQualityFilter" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PageCommander]
        val lib1 = library().withId(1).withMemberCount(1000).get
        val lib2 = library().withId(2).withMemberCount(0).withDesc("bar").get
        val lib3 = library().withId(3).withMemberCount(0).withDesc("nice log desc for good seo you know").get
        val lib4 = library().withId(4).withMemberCount(0).get
        val lib5 = library().withId(5).withMemberCount(1).get
        val lib6 = library().withId(6).withMemberCount(2).get
        val lib7 = library().withId(7).withMemberCount(0).withDesc("bar").get
        val lib8 = library().withId(8).withMemberCount(100).withDesc("super long desc that should not help me at all").get
        val lib9 = library().withId(8).withMemberCount(0).get
        val counts = Map(
          lib1.id.get -> 3,
          lib2.id.get -> 4,
          lib3.id.get -> 3,
          lib4.id.get -> 25,
          lib5.id.get -> 1,
          lib6.id.get -> 0,
          lib7.id.get -> 4,
          lib8.id.get -> 1,
          lib9.id.get -> 4
        )
        val filtered = commander.secondQualityFilter(Seq(lib1, lib2, lib3, lib4, lib5, lib6, lib7), counts)
        filtered.map(_.id.get) === Seq(lib1, lib2, lib3, lib4, lib7).map(_.id.get)
      }
    }

  }
}
