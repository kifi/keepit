package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ FakeRecommendationsCommander, RecommendationsCommander }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule, ElectronicMailRepo }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model.{ RecoInfo, LibraryRecoInfo }
import com.keepit.curator.{ FakeCuratorServiceClientImpl, CuratorServiceClient, FakeCuratorServiceClientModule }
import com.keepit.model.ExperimentType
import com.keepit.model._
import LibraryFactory._, LibraryFactoryHelper._
import UserFactory._, UserFactoryHelper._
import KeepFactory._, KeepFactoryHelper._
import LibraryMembershipFactory._, LibraryMembershipFactoryHelper._
import LibraryInviteFactory._, LibraryInviteFactoryHelper._
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

  def createLibWithKeeps(label: String, numLibraries: Int = 3, numKeeps: Int = 3)(implicit injector: Injector): Seq[(Library, Seq[Keep])] = {
    val Array(username, libName) = label.split('/')

    db.readWrite { implicit rw =>
      val libOwner = user().withName("User", username.toUpperCase).withUsername(username).saved
      libraries(numLibraries).zipWithIndex.map {
        case (lib, idx) =>
          val libSlug = s"$libName-l$idx"
          val libTitle = libSlug.replace("-", " ").toUpperCase
          val savedLib = lib.withName(libTitle).withSlug(libSlug).withUser(libOwner).published().saved

          // add 5 keeps to each library
          val savedKeeps = keeps(numKeeps).zipWithIndex.map {
            case (keep, idx) =>
              keep.withLibrary(savedLib).withTitle(s"K$idx $libTitle").saved
          }

          (savedLib, savedKeeps)
      }
    }
  }

  "ActivityFeedEmailSender" should {
    "work" in {
      withDb(modules: _*) { implicit injector =>
        val sender = inject[ActivityFeedEmailSender]

        val (user1, user2) = db.readWrite { implicit rw =>
          (
            user().withEmailAddress("u1@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved,
            user().withEmailAddress("u2@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved
          )
        }

        val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]

        // setup Lib Recos
        val user1Libs = createLibWithKeeps("u1/lib1-reco")
        val user2Libs = createLibWithKeeps("u2/lib2-reco")
        for {
          (user, libs) <- Seq((user1, user1Libs), (user2, user2Libs))
        } yield {
          curator.topLibraryRecosExpectations(user.id.get) = libs.map {
            case (lib, _) => LibraryRecoInfo(userId = user.id.get, libraryId = lib.id.get, masterScore = 8f, explain = "")
          }
        }

        def toRecos(user: User, libs: Seq[(Library, Seq[Keep])]): Seq[RecoInfo] = libs.take(5).map {
          case (_, keeps) =>
            RecoInfo(userId = user.id, uriId = keeps.head.uriId, score = 8f, explain = None, attribution = None)
        }.take(5)

        // setup URI Recos
        curator.fakeTopRecos = Map(
          user1.id.get -> toRecos(user1, createLibWithKeeps("u1/uri1-reco")),
          user2.id.get -> toRecos(user2, createLibWithKeeps("u2/uri2-reco"))
        )

        // setup latest keeps in libraries
        db.readWrite { implicit rw =>
          createLibWithKeeps("u1/followed1") foreach {
            case (lib, keeps) => membership().withLibraryFollower(lib, user1.id.get).saved
          }
          createLibWithKeeps("u2/followed2") foreach {
            case (lib, keeps) => membership().withLibraryFollower(lib, user2.id.get).saved
          }
        }

        // setup library invites
        db.readWrite { implicit rw =>
          createLibWithKeeps("u1/invite1", 3, 0) foreach {
            case (lib, _) => invite().toUser(user1).fromLibraryOwner(lib).saved
          }
          createLibWithKeeps("u2/invite2", 3, 0) foreach {
            case (lib, _) => invite().toUser(user2).fromLibraryOwner(lib).saved
          }
        }

        val senderF = sender()
        Await.ready(senderF, Duration(5, "seconds"))

        val email1 :: email2 :: Nil = db.readOnlyMaster { implicit s => inject[ElectronicMailRepo].all() }.
          sortBy { _.to.head.address }

        val html1: String = email1.htmlBody
        val html2: String = email2.htmlBody

        email1.to === Seq(EmailAddress("u1@kifi.com"))
        email2.to === Seq(EmailAddress("u2@kifi.com"))

        // test library recos
        // library names
        html1 must contain("LIB1 RECO L0")
        html2 must contain("LIB2 RECO L0")
        // library urls
        html1 must contain("/u1/lib1-reco-l0")
        html2 must contain("/u2/lib2-reco-l0")

        // test URI recos
        html1 must contain("K0 URI1 RECO L0")
        html2 must contain("K0 URI2 RECO L0")

        // test new keeps in libraries followed
        // library names
        html1 must contain("FOLLOWED1 L0")
        html2 must contain("FOLLOWED2 L0")
        // library urls
        html1 must contain("/u1/followed1-l0")
        html2 must contain("/u2/followed2-l0")
        // keep titles
        html1 must contain("K0 FOLLOWED1 L0")
        html2 must contain("K0 FOLLOWED2 L0")

        // test pending library invites
        // invited by
        html1 must contain("Invited by User U1")
        html2 must contain("Invited by User U2")
        // library names
        html1 must contain("INVITE1 L0")
        html2 must contain("INVITE2 L0")
        // library urls
        html1 must contain("/u1/invite1-l0")
        html2 must contain("/u2/invite2-l0")

      }
    }
  }

}
