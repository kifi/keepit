package com.keepit.commanders.emails

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ FakeRecommendationsCommander, RecommendationsCommander }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule, ElectronicMailRepo }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model.{ RecoInfo, LibraryRecoInfo }
import com.keepit.curator.{ FakeCuratorServiceClientImpl, CuratorServiceClient, FakeCuratorServiceClientModule }
import com.keepit.model.ExperimentType
import com.keepit.model._
import LibraryFactory._, LibraryFactoryHelper._, UserFactory._, UserFactoryHelper._, KeepFactory._, KeepFactoryHelper._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ActivityFeedEmailSenderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    ProdShoeboxServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeABookServiceClientModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule()
  )

  "ActivityFeedEmailSender" should {
    "work" in {
      withDb(modules: _*) { implicit injector =>
        val sender = inject[ActivityFeedEmailSender]

        val (user1, user2, user1Libs, user2Libs) = db.readWrite { implicit rw =>
          val libOwner = user().withUsername("joe").saved
          val (u1libs, u2libs) = libraries(10).zipWithIndex.map {
            case (lib, lidx) =>
              val savedLib = lib.withUser(libOwner).withName(s"Lib $lidx").withSlug(s"lib$lidx").published().saved
              val savedKeeps = keeps(5).zipWithIndex.map {
                case (keep, kidx) => keep.withLibrary(savedLib).withTitle(s"Keep $kidx-$lidx").saved
              }
              (savedLib, savedKeeps)
          }.splitAt(5)

          (
            user().withEmailAddress("u1@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved,
            user().withEmailAddress("u2@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved,
            u1libs,
            u2libs
          )
        }

        val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]
        val fakeTopRecos = collection.mutable.Map[Id[User], Seq[RecoInfo]]().withDefaultValue(Seq.empty)

        for {
          (user, libs) <- Seq((user1, user1Libs), (user2, user2Libs))
        } yield {
          fakeTopRecos(user.id.get) = libs flatMap {
            case (_, keeps) => keeps map { keep =>
              RecoInfo(userId = user.id, uriId = keep.uriId, score = 8f, explain = None, attribution = None)
            }
          }

          curator.topLibraryRecosExpectations(user.id.get) = libs.map {
            case (lib, _) =>
              LibraryRecoInfo(userId = user.id.get, libraryId = lib.id.get, masterScore = 8f, explain = "")
          }
        }

        curator.fakeTopRecos = fakeTopRecos.toMap

        val senderF = sender()
        Await.ready(senderF, Duration(5, "seconds"))

        val email1 :: email2 :: Nil = db.readOnlyMaster { implicit s => inject[ElectronicMailRepo].all() }.
          sortBy { _.to.head.address }

        val html1: String = email1.htmlBody
        val html2: String = email2.htmlBody

        email1.to === Seq(EmailAddress("u1@kifi.com"))
        html1 must contain("/joe/lib0")
        html1 must not contain "/joe/lib5"
        html1 must contain("Lib 0")
        html1 must contain("Keep 0-0")
        html1 must not contain "Keep 0-5"

        email2.to === Seq(EmailAddress("u2@kifi.com"))
        html2 must contain("/joe/lib5")
        html2 must not contain "/joe/lib4"
        html2 must contain("Lib 5")
        html2 must contain("Keep 0-5")
        html2 must not contain "Keep 0-4"
      }
    }
  }

}
