package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ ElectronicMailRepo, EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model.{ LibraryRecoInfo, RecoInfo }
import com.keepit.curator.{ CuratorServiceClient, FakeCuratorServiceClientImpl, FakeCuratorServiceClientModule }
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ ExperimentType, _ }
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
            user().withName("Kifi", "User1").withEmailAddress("u1@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved,
            user().withName("Kifi", "User2").withEmailAddress("u2@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved
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

        // setup friend requests
        val friendRequestRepo = inject[FriendRequestRepo]
        db.readWrite { implicit rw =>
          friendRequestRepo.save(FriendRequest(senderId = user2.id.get, recipientId = user1.id.get, messageHandle = None))
          friendRequestRepo.save(FriendRequest(senderId = user1.id.get, recipientId = user2.id.get, messageHandle = None))
        }

        // setup friend created libraries
        val connRepo = inject[UserConnectionRepo]

        db.readWrite { implicit rw =>
          for {
            (user, userIdx) <- Seq(user1, user2).zipWithIndex
            i <- 0 to 2 // 3 different lib owner will create 2 libraries (to test library owner diversity)
            (lib, keeps) <- createLibWithKeeps(s"u${userIdx + 1}/friendCreated$i", 2, 1)
          } yield {
            connRepo.save(UserConnection(user1 = user.id.get, user2 = lib.ownerId))
          }
        }

        // setup friend followed libraries
        db.readWrite { implicit rw =>
          for {
            (user, userIdx) <- Seq(user1, user2).zipWithIndex
            friend1 = UserFactory.user().withName("John", s"Doe$userIdx").saved
            friend2 = UserFactory.user().withName("Bobby", s"Tullip$userIdx").saved
            i <- 0 to 1
            (lib, keeps) <- createLibWithKeeps(s"u${userIdx + 1}/friendFollowed$i", 1, 1)
          } yield {
            connRepo.save(UserConnection(user1 = user.id.get, user2 = friend1.id.get))
            connRepo.save(UserConnection(user1 = user.id.get, user2 = friend2.id.get))

            membership().withLibraryFollower(lib, friend1).saved
            membership().withLibraryFollower(lib, friend2).saved
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

        // test friend requests
        html1 must contain(s""">${user2.fullName}</a>""")
        html2 must contain(s""">${user1.fullName}</a>""")

        // test friend created libraries
        html1 must contain("u1/friendCreated0")
        html1 must contain("u1/friendCreated1")
        html1 must contain("u1/friendCreated2")
        html2 must contain("u2/friendCreated0")
        html2 must contain("u2/friendCreated1")
        html2 must contain("u2/friendCreated2")

        // test friend followed libraries
        html1 must contain("u1/friendFollowed0")
        html1 must contain("John Doe0")
        html1 must contain("Bobby Tullip0")
        html2 must contain("u2/friendFollowed1")
        html2 must contain("John Doe1")
        html2 must contain("Bobby Tullip1")

      }
    }
  }

}
