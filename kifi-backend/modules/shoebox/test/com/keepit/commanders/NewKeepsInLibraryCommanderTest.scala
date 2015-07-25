package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike

class NewKeepsInLibraryCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule()
  )

  "LibraryCommander" should {
    "create libraries, memberships & invites" in {
      withDb(modules: _*) { implicit injector =>
        val factory = inject[ShoeboxTestFactory]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience, keeps: Seq[Keep]) = factory.setupLibraryKeeps()
        val keepIds = keeps.map(_.id.get)

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
          allLibs.map(_.description) === Seq(None, None, None)
          allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.DISCOVERABLE)
          libraryMembershipRepo.count === 5
        }

        val commander = inject[NewKeepsInLibraryCommander]
        commander.getLastEmailViewedKeeps(userIron.id.get, 100).size === 0
        commander.getLastEmailViewedKeeps(userCaptain.id.get, 100).map(_.id.get) === Seq(keepIds(2), keepIds(3), keepIds(4), keepIds(6), keepIds(7))
        commander.getLastEmailViewedKeeps(userAgent.id.get, 100).map(_.id.get) === Seq(keepIds(2), keepIds(0), keepIds(3), keepIds(4), keepIds(6), keepIds(7), keepIds(1))
        commander.getLastEmailViewedKeeps(userAgent.id.get, 2).map(_.id.get) === Seq(keepIds(2), keepIds(0))
        commander.getLastEmailViewedKeeps(userCaptain.id.get, 2).map(_.id.get) === Seq(keepIds(2), keepIds(3))
        commander.getLastEmailViewedKeeps(userAgent.id.get, 1).map(_.id.get) === Seq(keepIds(2))
        commander.getLastEmailViewedKeeps(userCaptain.id.get, 1).map(_.id.get) === Seq(keepIds(2))
      }
    }
  }
}
