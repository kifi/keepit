package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource, LibraryRecoInfo }
import com.keepit.curator.{ FakeCuratorServiceClientModule, FakeCuratorServiceClientImpl, CuratorServiceClient }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class RecommendationsCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
    FakeActorSystemModule()
  )

  "RecommendationsCommanderTest" should {
    implicit val config = PublicIdConfiguration("secret key")
    "topPublicLibraryRecos" should {
      "work" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[RecommendationsCommander]
          val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]
          val (user1, lib1, lib2, lib3) = db.readWrite { implicit rw =>
            val owner = user().saved
            (
              user().saved,
              library().withUser(owner).withName("Scala").published().saved,
              library().withUser(owner).withName("Secret").saved, // secret - shouldn't be a reco
              library().withUser(owner).withName("Java").published().saved
            )
          }
          db.readWrite { implicit rw =>
            keep().withLibrary(lib1).saved
            keep().withLibrary(lib2).saved
            keep().withLibrary(lib2).saved
          }

          curator.topLibraryRecosExpectations(user1.id.get) = Seq(
            LibraryRecoInfo(user1.id.get, lib3.id.get, 7, ""),
            LibraryRecoInfo(user1.id.get, lib2.id.get, 8, ""),
            LibraryRecoInfo(user1.id.get, lib1.id.get, 8, "")
          )

          val recosF = commander.topPublicLibraryRecos(user1.id.get, 5, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, context = None)
          val recos = Await.result(recosF.map { _.recos }, Duration(5, "seconds")).map(_._2)
          recos.size === 2
          recos(0).itemInfo.name === "Java"
          recos(1).itemInfo.name === "Scala"
        }
      }
    }
  }

}
