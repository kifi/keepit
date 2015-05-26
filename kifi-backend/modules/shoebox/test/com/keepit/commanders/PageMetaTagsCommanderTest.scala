package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
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
import com.keepit.model._
import com.keepit.rover.{ FakeRoverServiceClientImpl, RoverServiceClient, FakeRoverServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.Await
import scala.concurrent.duration._

class PageMetaTagsCommanderTest extends Specification with ShoeboxTestInjector with NoTimeConversions {

  def modules = FakeKeepImportsModule() ::
    FakeExecutionContextModule() ::
    FakeShoeboxStoreModule() ::
    FakeSearchServiceClientModule() ::
    FakeCortexServiceClientModule() ::
    FakeShoeboxServiceModule() ::
    FakeUserActionsModule() ::
    FakeCuratorServiceClientModule() ::
    FakeABookServiceClientModule() ::
    FakeSocialGraphModule() ::
    FakeSliderHistoryTrackerModule() ::
    FakeRoverServiceClientModule() ::
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

  private def setDescriptionForKeep(keep: Keep, description: String)(implicit injector: Injector): Unit = {
    val rover = inject[RoverServiceClient].asInstanceOf[FakeRoverServiceClientImpl]
    rover.setDescriptionForUri(keep.uriId, description)
  }

  "PageMetaTagsCommander" should {

    "selectKeepsDescription with long desc" in {
      withDb(modules: _*) { implicit injector =>

        val longDescription = "this is a very very very very very very very very very very very very very very very very very very very very very very very long description"
        val shortDescription = "this is a very very very very very very very short one"

        val commander = inject[PageMetaTagsCommander]
        val lib = db.readWrite { implicit s =>
          val lib = library().saved
          setDescriptionForKeep(keep().withLibrary(lib).saved, longDescription)
          setDescriptionForKeep(keep().withLibrary(lib).saved, shortDescription)
          lib
        }
        Await.result(commander.selectKeepsDescription(lib.id.get), 5 seconds).get === longDescription
      }
    }

    "selectKeepsDescription with short desc" in {
      withDb(modules: _*) { implicit injector =>

        val longDescription = "this is a very very very very very very very long one"
        val shortDescription = "this is a very short"

        val commander = inject[PageMetaTagsCommander]
        val lib = db.readWrite { implicit s =>
          val lib = library().saved
          setDescriptionForKeep(keep().withLibrary(lib).saved, longDescription)
          setDescriptionForKeep(keep().withLibrary(lib).saved, shortDescription)
          lib
        }
        Await.result(commander.selectKeepsDescription(lib.id.get), 5 seconds).get === longDescription
      }
    }

  }
}
