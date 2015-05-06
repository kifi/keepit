package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.emails.activity.{ ActivityFeedEmailQueueHelper, SendActivityEmailToUserMessage }
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
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
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import com.kifi.franz.SQSQueue
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
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule(),
    FakeActivityFeedEmailQueueModule()
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
        val (user1, user2) = db.readWrite { implicit rw =>
          val u1 = user().withName("Kifi", "User1").withEmailAddress("u1@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved
          val u2 = user().withName("Kifi", "User2").withEmailAddress("u2@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved
          val u3 = user().withName("Kifi", "User3").withEmailAddress("u3@kifi.com").withExperiments(ExperimentType.ACTIVITY_EMAIL).saved
          keeps(20).foreach(_.withUser(u1).saved)
          keeps(20).foreach(_.withUser(u2).saved)
          keeps(2).foreach(_.withUser(u2).saved)
          (u1, u2)
        }

        val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]

        val randomFollowers = db.readWrite { implicit rw => users(30).map(_.saved) }

        // setup Lib Recos with followers
        val user1Libs = createLibWithKeeps("u1/lib1-reco", 10)
        val user2Libs = createLibWithKeeps("u2/lib2-reco", 10)
        for {
          (user, libs) <- Seq((user1, user1Libs), (user2, user2Libs))
        } yield {

          // create followers for each library
          db.readWrite { implicit session =>
            libs.foreach {
              case (lib, _) =>
                val followers = util.Random.shuffle(randomFollowers).take(util.Random.nextInt(randomFollowers.size))
                followers.foreach { user => membership().withLibraryFollower(lib, user).saved }
            }
          }

          curator.topLibraryRecosExpectations(user.id.get) = libs.map {
            case (lib, _) =>
              LibraryRecoInfo(userId = user.id.get, libraryId = lib.id.get, masterScore = 8f, explain = "")
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

        // setup new followers of user's libraries
        db.readWrite { implicit rw =>
          Seq(user1, user2).zipWithIndex map {
            case (user, userIdx) =>
              val lib = library().withUser(user).withSlug(s"u${userIdx + 1}/newFollowersMyLibs").published().saved
              keep().withLibrary(lib).saved

              val followers = util.Random.shuffle(randomFollowers).take(util.Random.nextInt(randomFollowers.size))
              followers.foreach { follower => membership().withLibraryFollower(lib, follower).saved }
          }
        }

        val activityQueueHelper = inject[ActivityFeedEmailQueueHelper]
        Await.ready(activityQueueHelper.addToQueue(), Duration(5, "seconds"))
        Await.ready(activityQueueHelper.processQueue(), Duration(10, "seconds"))
        inject[WatchableExecutionContext].drain()

        1 === 1
        //        val emails = db.readOnlyMaster { implicit s => inject[ElectronicMailRepo].all() }.sortBy(_.to.head.address)
        //        val email1 :: email2 :: Nil = emails.filter(_.category == NotificationCategory.toElectronicMailCategory(NotificationCategory.User.ACTIVITY))
        //
        //        val activityEmails = db.readOnlyMaster { implicit s => inject[ActivityEmailRepo].all }
        //        activityEmails.size === 2
        //
        //        val activityEmail1 = activityEmails.find(_.userId == user1.id.get).get
        //        activityEmail1.otherFollowedLibraries.get.size === 4
        //        activityEmail1.userFollowedLibraries.get.size === 1
        //        activityEmail1.libraryRecommendations.get.size === 3
        //
        //        // useful for quick-and-dirty testing
        //        //        val html1: String = email1.htmlBody
        //        //        val html2: String = email2.htmlBody
        //        //        val fw = new java.io.FileWriter("activity.html")
        //        //        fw.write(html1)
        //        //        fw.close()
        //
        //        email1.to === Seq(EmailAddress("u1@kifi.com"))
        //        email2.to === Seq(EmailAddress("u2@kifi.com"))

      }
    }

    "adds and processes jobs from a queue" in {
      withDb(modules: _*) { implicit injector =>
        val fakeQueue = inject[SQSQueue[SendActivityEmailToUserMessage]].asInstanceOf[FakeActivityEmailQueue]

        val sender = inject[ActivityFeedEmailSender]
        var (u1, u2, u3) = db.readWrite { implicit rw =>
          val u1 = user().withName("Kifi", "User1").withEmailAddress("u1@kifi.com").saved
          val u2 = user().withName("Kifi", "User2").withEmailAddress("u2@kifi.com").saved
          val u3 = user().withName("Kifi", "User3").withEmailAddress("u3@kifi.com").saved
          keeps(20).foreach(_.withUser(u1).saved)
          keeps(20).foreach(_.withUser(u2).saved)

          // ensure that user with not enough keeps won't get queued to receive an email
          keeps(2).foreach(_.withUser(u3).saved)

          (u1, u2, u3)
        }

        val activityQueueHelper = inject[ActivityFeedEmailQueueHelper]

        Await.ready(activityQueueHelper.addToQueue(), Duration(5, "seconds"))
        fakeQueue.messages.size === 6
        fakeQueue.lockedMessages.size === 0
        fakeQueue.consumedMessages.size === 0

        //        fakeQueue.messages(0).body.userId === u1.id.get
        //        fakeQueue.messages(1).body.userId === u2.id.get

        Await.ready(activityQueueHelper.processQueue(), Duration(5, "seconds"))
        fakeQueue.messages.size === 0
        fakeQueue.lockedMessages.size === 0
        fakeQueue.consumedMessages.size === 6
      }
    }
  }

}
