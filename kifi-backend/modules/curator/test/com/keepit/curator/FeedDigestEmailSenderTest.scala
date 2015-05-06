package com.keepit.curator

import com.keepit.model.UserFactory._
import com.google.inject.Injector
import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.template.helpers.{ libraryName, libraryUrl }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.email.{ RecentInterestRankStrategy, FeedDigestEmailSender }
import com.keepit.curator.model.{ SeedAttribution, TopicAttribution, UriRecommendationRepo, UserAttribution }
import com.keepit.curator.queue.{ FakeFeedDigestEmailQueue, FakeFeedDigestEmailQueueModule, SendFeedDigestToUserMessage }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ SocialId, SocialNetworks }
import com.kifi.franz.SQSQueue
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.common.actor.FakeActorSystemModule

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FeedDigestEmailSenderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeFeedDigestEmailQueueModule(),
    FakeActorSystemModule())

  implicit def userToIdInt(user: User): Int = user.id.get.id.toInt

  "FeedDigestEmailSender" should {

    "adds and processes jobs from a queue" in {
      withDb(modules: _*) { implicit injector =>
        val shoebox = shoeboxClientInstance()
        val fakeQueue = inject[SQSQueue[SendFeedDigestToUserMessage]].asInstanceOf[FakeFeedDigestEmailQueue]

        val sender = inject[FeedDigestEmailSender]
        val user1 = makeUser(42, shoebox)
        val user2 = makeUser(43, shoebox)

        Await.ready(sender.addToQueue(Seq(user1.id.get, user2.id.get)), Duration(5, "seconds"))
        fakeQueue.messages.size === 2
        fakeQueue.lockedMessages.size === 0
        fakeQueue.consumedMessages.size === 0

        Await.ready(sender.processQueue(), Duration(5, "seconds"))
        // expect messages to fail to be consumed b/c of missing social user infos
        fakeQueue.messages.size === 0
        fakeQueue.lockedMessages.size === 2
        fakeQueue.consumedMessages.size === 0

        // stub requirements for messages to be consumed
        shoebox.socialUserInfosByUserId(user1.id.get) = List()
        shoebox.socialUserInfosByUserId(user2.id.get) = List(SocialUserInfo(fullName = "Muggsy Bogues", profileUrl = Some("http://fb.com/me"), networkType = SocialNetworks.FACEBOOK, socialId = SocialId("123")))
        fakeQueue.unlockAll()

        Await.ready(sender.processQueue(), Duration(5, "seconds"))
        fakeQueue.messages.size === 0
        fakeQueue.lockedMessages.size === 0
        fakeQueue.consumedMessages.size === 2
      }
    }

    def setupToSend(db: Database)(implicit injector: Injector) = {
      val shoebox = shoeboxClientInstance()
      val sender = inject[FeedDigestEmailSender]
      val user1 = makeUser(42, shoebox)
      val user2 = makeUser(43, shoebox)
      val uriRecoRepo = inject[UriRecommendationRepo]

      shoebox.socialUserInfosByUserId(user1.id.get) = List()
      shoebox.socialUserInfosByUserId(user2.id.get) = List(SocialUserInfo(fullName = "Muggsy Bogues", profileUrl = Some("http://fb.com/me"), networkType = SocialNetworks.FACEBOOK, socialId = SocialId("123")))

      val friends = users(6).get
      val (friend1, friend2) = (friends(0), friends(1))

      val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
      val friendIds = friends.map(_.id.get)
      abook.addFriendRecommendationsExpectations(user1.id.get, friendIds)
      abook.addFriendRecommendationsExpectations(user2.id.get, friendIds)

      shoebox.saveUsers(friends: _*)
      shoebox.saveUserImageUrl(44, "//url.com/u44.jpg")
      shoebox.saveUserImageUrl(48, "//url.com/u48.jpg")
      shoebox.saveUserImageUrl(49, "//url.com/0.jpg")

      val savedRecoModels = db.readWrite { implicit rw =>
        Seq(
          {
            val tuple = makeCompleteUriRecommendation(1, 42, 8f, "https://www.kifi.com", 10000)
            tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
              user = Some(UserAttribution(friends = Seq(friend1, friend2).map(_.id.get), others = 1, None)),
              topic = Some(TopicAttribution("Reading"))
            )))
          }, {
            val tuple = makeCompleteUriRecommendation(2, 42, 9f, "https://www.google.com", 2500)
            tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
              user = Some(UserAttribution(friends = Seq(friend1.id.get), others = 2, None)),
              topic = Some(TopicAttribution("Searching"))
            )))
          },
          makeCompleteUriRecommendation(3, 43, 11f, "http://www.42go.com"),
          makeCompleteUriRecommendation(4, 43, 8.5f, "http://www.yahoo.com"),
          // this isn't in the recommendation list because image width is too small
          makeCompleteUriRecommendation(5, 43, 9f, "http://www.lycos.com", 250, Some(200)), {
            // this isn't in recommendation list b/c it has already been sent
            val tuple = makeCompleteUriRecommendation(6, 42, 9f, "http://www.excite.com")
            tuple.copy(_2 = tuple._2.withLastPushedAt(currentDateTime))
          },
          // shouldn't be in reco list b/c it's below threshold (8)
          makeCompleteUriRecommendation(7, 43, 7.99f, "https://www.bing.com"),
          // shouldn't be in reco list b/c image is too tall
          makeCompleteUriRecommendation(uriId = 8, userId = 43, masterScore = 9, url = "https://www.youtube.com/watch?v=BROWqjuTM0g", summaryImageHeight = Some(901)), {
            // shouldn't be in reco list b/c trashed
            val tup = makeCompleteUriRecommendation(9, 42, 10f, "http://www.myspace.com")
            tup.copy(_2 = tup._2.copy(trashed = true))
          }, {
            // shouldn't be in reco list b/c kept
            val tup = makeCompleteUriRecommendation(10, 42, 10f, "http://www.apple.com")
            tup.copy(_2 = tup._2.copy(kept = true))
          }
        ).map(tuple => saveUriModels(tuple, shoebox))
      }

      (shoebox, sender, savedRecoModels, user1, user2, friends)
    }

    "sends not-already-pushed keeps to users" in {
      withDb(modules: _*) { implicit injector =>
        val (shoebox, sender, savedRecoModels, user1, user2, friends) = setupToSend(db)

        val sumU42F = sender.sendToUser(user1.id.get)
        val sumU43F = sender.sendToUser(user2.id.get)
        val sumU42 = Await.result(sumU42F, Duration(5, "seconds"))
        val sumU43 = Await.result(sumU43F, Duration(5, "seconds"))

        sumU42.recommendations.size === 2

        // lycos and excite should not be included because:
        // - lycos does not pass the image width requirement
        // - excite has been sent already
        sumU43.recommendations.size === 2

        // 2 sent to users
        // 2 copied to QA
        shoebox.sentMail.size === 2

        val (mail42, mail43) = {
          val (xs, ys) = shoebox.sentMail.filter(_.senderUserId.isDefined).partition(_.senderUserId.get == Id[User](42))
          (xs.head, ys.head)
        }

        mail42.senderUserId.get must beEqualTo(Id[User](42))
        val mail42body = mail42.htmlBody.value
        val mail42text = mail42.textBody.get.value
        // checking the domain-to-name mapper
        mail42body must contain(">www.kifi&#8203;.com<")
        mail42body must contain(">Google<")

        // check that uri's for the recos are in the emails
        mail42body must contain("/r/e/1/recos/view?id=" + savedRecoModels(0)._1.externalId)
        mail42text must contain("/r/e/1/recos/view?id=" + savedRecoModels(0)._1.externalId)
        //mail42body must contain("/r/e/1/recos/keep?id=" + savedRecoModels(0)._1.externalId)
        //mail42body must contain("/r/e/1/recos/send?id=" + savedRecoModels(1)._1.externalId)

        // others-who-kept messages
        mail42body must contain("2 connections and 1 other kept this")
        mail42text must contain("2 connections and 1 other kept this")
        mail42body must contain("1 connection and 2 others kept this")
        mail42text must contain("1 connection and 2 others kept this")

        // read times
        mail42body must contain("45 min")
        mail42body must contain("15 min")
        mail42text must contain("45 min")
        mail42text must contain("15 min")

        // TopicAttribution
        mail42body must contain("interested in: Searching")
        mail42body must contain("interested in: Reading")
        mail42text must contain("interested in: Searching")
        mail42text must contain("interested in: Reading")

        mail43.senderUserId.get must beEqualTo(Id[User](43))
        val mail43body = mail43.htmlBody.toString

        mail43body must not contain "lycos.com"
        mail43body must not contain "excite.com"
        mail43body must not contain savedRecoModels(4)._1.externalId.toString
        mail43body must not contain savedRecoModels(5)._1.externalId.toString

        mail43body must contain("5 others kept this")

        // check that uri's for the recos are in the emails
        //mail43body must contain("/r/e/1/recos/keep?id=" + savedRecoModels(2)._1.externalId)
        //mail43body must contain("/r/e/1/recos/send?id=" + savedRecoModels(3)._1.externalId)

        // conditionally show the "Connect Facebook" link if they haven't connected facebook
        mail42body must contain("Connect Facebook")
        mail43body must not contain "Connect Facebook"

        val sentRecoIds = Set(1L, 2L, 3L, 4L) // reco Ids that were just sent
        savedRecoModels.forall { models =>
          val (uri, reco, uriSumm) = models
          db.readOnlyMaster { implicit s =>
            val freshReco = inject[UriRecommendationRepo].get(reco.id.get)
            if (sentRecoIds.contains(uri.id.get.id)) {
              freshReco.lastPushedAt must beSome
              freshReco.viewed === 1
            } else {
              freshReco.lastPushedAt must (if (reco.id.get.id == 6L) beSome else beNone)
              freshReco.viewed === 0
            }
          }
        }
      }
    }

    "sends digest email with highest recent interest" in {
      withDb(modules: _*) { implicit injector =>
        val (shoebox, sender, savedRecoModels, user1, user2, friends) = setupToSend(db)

        val uriModels = db.readWrite { implicit rw =>
          Seq(
            makeCompleteUriRecommendation(40, user2, 8.1f, "http://espn.com", allScores = defaultAllScores.copy(recentInterestScore = 8f)),
            makeCompleteUriRecommendation(41, user2, 8.2f, "http://digg.com", allScores = defaultAllScores.copy(recentInterestScore = 7f)),
            makeCompleteUriRecommendation(42, user2, 8.3f, "http://hotornot.com", allScores = defaultAllScores.copy(recentInterestScore = 6f))
          ).map(saveUriModels(_, shoebox))
        }
        val sumU43F = sender.sendToUser(user2.id.get, RecentInterestRankStrategy)
        val sumU43 = Await.result(sumU43F, Duration(5, "seconds"))

        sumU43.recommendations.size === 3
        sumU43.mailSent === true

        def recoId(index: Int) = sumU43.recommendations(index).uriRecommendation.id.get
        recoId(0) === uriModels(0)._2.id.get
        recoId(1) === uriModels(1)._2.id.get
        recoId(2) === uriModels(2)._2.id.get
      }
    }

    "sends new keeps in libraries" in {
      withDb(modules: _*) { implicit injector =>
        val (shoebox, sender, savedRecoModels, user1, user2, friends) = setupToSend(db)

        val uriModels = db.readWrite { implicit rw =>
          Seq(
            // setting the master score low so they aren't in the recommendation list
            makeCompleteUriRecommendation(40, user2, 1f, "http://whitehouse.gov"),
            makeCompleteUriRecommendation(41, user2, 1f, "http://craigslist.org"),
            makeCompleteUriRecommendation(42, user2, 1f, "http://theverge.com")
          ).map(saveUriModels(_, shoebox))
        }

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val keeps = {
          // the 3rd reco in savedRecoModels is expected to be one of the recommended keeps
          // we're adding this keep to newKeepsInLibrary to ensure it's not in the email twice
          val (firstRecoUri, firstReco, _) = savedRecoModels(2)
          val keep0 = Keep(title = None, userId = user2.id.get, url = firstRecoUri.url, urlId = Id[URL](firstRecoUri.id.get.id),
            uriId = firstRecoUri.id.get, source = KeepSource.keeper, createdAt = t1, libraryId = Some(Id[Library](3)),
            inDisjointLib = false, visibility = LibraryVisibility.SECRET)

          val keep1 = Keep(title = Some("Espn"), userId = user2.id.get, url = "http://espn.com", urlId = Id[URL](40),
            uriId = uriModels(0)._1.id.get, source = KeepSource.keeper, createdAt = t1, libraryId = Some(Id[Library](1)),
            inDisjointLib = false, visibility = LibraryVisibility.SECRET)
          val keep2 = Keep(title = Some("Digg"), userId = user2.id.get, url = "http://digg.com", urlId = Id[URL](41),
            uriId = uriModels(1)._1.id.get, source = KeepSource.keeper, createdAt = t1, libraryId = Some(Id[Library](2)),
            inDisjointLib = false, visibility = LibraryVisibility.SECRET)
          val keep3 = Keep(title = Some("The Verge"), userId = user2.id.get, url = "http://theverge.com", urlId = Id[URL](42),
            uriId = uriModels(2)._1.id.get, source = KeepSource.keeper, createdAt = t1, libraryId = Some(Id[Library](4)),
            inDisjointLib = false, visibility = LibraryVisibility.SECRET)
          Seq(keep0, keep1, keep2, keep3)
        }

        shoebox.allUserExperiments(user2.id.get) = Set(UserExperiment(experimentType = ExperimentType.LIBRARIES, userId = user2.id.get))
        shoebox.newKeepsInLibrariesExpectation(user2.id.get) = keeps

        val sumU43F = sender.sendToUser(user2.id.get)
        val sumU43 = Await.result(sumU43F, Duration(5, "seconds"))

        // 1 sent to user
        // 1 copied to QA
        shoebox.sentMail.size === 1

        sumU43.recommendations.size === 2
        sumU43.newKeeps.size === 3

        val email = shoebox.sentMail(0)
        val html = email.htmlBody.value

        implicit def libId(id: Int) = Id[Library](id.toLong)
        def libName(id: Id[Library]): String = libraryName(id).body
        def libUrl(id: Id[Library]): String = libraryUrl(id, "libReco").body

        // match the html with strings we expect to be in it
        Seq(libName(4), libUrl(4), libName(1), libUrl(1), libName(2), libUrl(2),
          "whitehouse&#8203;.gov", "craigslist", "The Verge").foreach { str: String =>
            html must contain(str)
          }

        // keep from library 3 should not be there because it is already a recommendation from the feed
        html must not contain libName(3)
        html must not contain libUrl(3)
      }
    }
  }

}
