package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.UserProfileTab
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.PageInfoFactory._
import com.keepit.model.PageInfoFactoryHelper._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class PageMetaTagsCommanderTest extends Specification with ShoeboxTestInjector {

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

  "UserProfileTab" should {
    "get by path" in {
      UserProfileTab("/joe") === UserProfileTab.Libraries
      UserProfileTab("/joe/") === UserProfileTab.Libraries
      UserProfileTab("/joe/libraries") === UserProfileTab.Libraries
      UserProfileTab("/joe/libraries/") === UserProfileTab.Libraries
      UserProfileTab("/joe/libraries/following") === UserProfileTab.FollowingLibraries
      UserProfileTab("/joe/libraries/following/") === UserProfileTab.FollowingLibraries
      UserProfileTab("/joe/libraries/invited") === UserProfileTab.InvitedLibraries
      UserProfileTab("/joe/libraries/invited/") === UserProfileTab.InvitedLibraries
      UserProfileTab("/joe/connections") === UserProfileTab.Connections
      UserProfileTab("/joe/connections/") === UserProfileTab.Connections
      UserProfileTab("/joe/followers") === UserProfileTab.Followers
      UserProfileTab("/joe/followers/") === UserProfileTab.Followers
    }

    "format titles" in {
      UserProfileTab.Libraries.title("G.I. Joe") === "G.I. Joe’s Libraries"
      UserProfileTab.FollowingLibraries.title("G.I. Joe") === "Libraries G.I. Joe Follows"
      UserProfileTab.InvitedLibraries.title("G.I. Joe") === "G.I. Joe’s Library Invitations"
      UserProfileTab.Connections.title("G.I. Joe") === "G.I. Joe’s Connections"
      UserProfileTab.Followers.title("G.I. Joe") === "G.I. Joe’s Followers"
    }
  }

  "PageMetaTagsCommander" should {

    "selectKeepsDescription with long desc" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PageMetaTagsCommander]
        val lib = db.readWrite { implicit s =>
          val lib = library().saved
          keep().withLibrary(lib).saved.pageInfo.withDescription("this is a very very very very very very very short one").saved
          keep().withLibrary(lib).saved.pageInfo.withDescription("this is a very very very very very very very very very very very very very very very very very very very very very very very long description").saved
          lib
        }
        db.readOnlyMaster { implicit s =>
          commander.selectKeepsDescription(lib.id.get).get === "this is a very very very very very very very very very very very very very very very very very very very very very very very long description"
        }
      }
    }

    "selectKeepsDescription with short desc" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[PageMetaTagsCommander]
        val lib = db.readWrite { implicit s =>
          val lib = library().saved
          keep().withLibrary(lib).saved.pageInfo.withDescription("this is a very very very very very very very long one").saved
          keep().withLibrary(lib).saved.pageInfo.withDescription("this is a very short").saved
          lib
        }
        db.readOnlyMaster { implicit s =>
          commander.selectKeepsDescription(lib.id.get).get === "this is a very very very very very very very long one"
        }
      }
    }

  }
}
