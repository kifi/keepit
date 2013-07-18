package com.keepit.learning.topicmodel

import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.BookmarkSource
import com.keepit.model.BookmarkFactory
import com.keepit.model.NormalizedURIFactory
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.UriTopicHelper
import com.keepit.model.UriTopic
import com.keepit.model.URLFactory
import com.keepit.model.User
import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import scala.math.log

class ExpertRecommenderTest extends Specification with DbSetupHelper{

  "Expert Recommender" should {
    "compute topic posterior" in {
      withDb() { implicit injector =>
        val (users, uris) = setup()

        val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
        db.readOnly{ implicit s =>
          uriTopicRepoA.count === uris.size
        }
        var v = List(1, 11, 21, 31, 41)
        var hits = v.map { i => uris(i - 1).id.get }
        var prob = rcmder.estimateTopicPosterior(hits)
        prob === Map(1 -> 0.2, 2 -> 0.2, 3 -> 0.2, 4 -> 0.2, 5 -> 0.2)

        v = List(1, 2, 3, 11, 12)
        hits = v.map { i => uris(i - 1).id.get }
        prob = rcmder.estimateTopicPosterior(hits)
        prob === Map(1 -> 0.6, 2 -> 0.4)
      }
    }

    "compute user hits" in {
       withDb() { implicit injector =>
         val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
         def uriId(x: Int) = Id[NormalizedURI](x)
         def userId(x: Int) = Id[User](x)

         val urisAndKeepers = List( (uriId(1), List(userId(1), userId(2), userId(3))),
                               (uriId(2), List(userId(2), userId(3)) ),
                               (uriId(3), List(userId(3))) )

         rcmder.userHits(urisAndKeepers) === Map(userId(1) -> 1, userId(2) -> 2, userId(3) -> 3)
       }
    }

    "get user public bookmarks" in {
      withDb() { implicit injector =>
        val (users, uris) = setup()
        val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
        val bms = rcmder.userPublicBookmarks(users.map{_.id.get}.toSet)
        bms.get(users(0).id.get).get.toSet === uris.slice(0, 10).map{_.id.get}.toSet
        bms.get(users(1).id.get).get.toSet === uris.slice(0, 10).map{_.id.get}.toSet
        bms.get(users(2).id.get).get.toSet === uris.slice(10, 20).map{_.id.get}.toSet
        bms.get(users(9).id.get).get.toSet === uris.slice(40, 50).map{_.id.get}.toSet
      }
    }

    "get all relevant bookmarks" in {
      withDb() { implicit injector =>
        val (users, uris) = setup()
        val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
        val numTopics = 5
        val uriIds = uris.map{_.id.get}
        (0 until numTopics).foreach{ i =>
          val correct = uriIds.slice(i*10, (i+1)*10).foldLeft(Map.empty[Id[NormalizedURI], Int]){(m, uri) => m + (uri -> (i + 1))}
          rcmder.allRelevantBookmarks(uriIds.toSet, List(i+1).toSet) === correct
        }
      }
    }

    "get user relevant bookmarks" in {
       withDb() { implicit injector =>
        def uriId(x: Int) = Id[NormalizedURI](x)
        val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
        val userUris = (0 until 20).map{uriId(_)}
        val allRelevantUris = Map( uriId(1) -> 1, uriId(2) -> 1, uriId(10) -> 2, uriId(50) -> 3 )
        rcmder.userRelevantBookmarks(userUris, allRelevantUris) === Map(1 -> List(uriId(2), uriId(1)), 2 -> List(uriId(10)))
      }
    }

    "get bookmark clicks" in {
      withDb() { implicit injector =>
        val (users, uris) = setup()
        val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
        val uriIds = uris.map{_.id.get}
        val allRelevantBms = rcmder.allRelevantBookmarks(uriIds.toSet, List(1, 3, 5).toSet)
        val bms = rcmder.userPublicBookmarks(users.map{_.id.get}.toSet)
        val clicks = 10
        val clicksMap = rcmder.getAllUserBookmarkClicks(users.map{_.id.get}.toSet, allRelevantBms, bms)
        (1 to users.size).foreach{ user =>
          val selfClicker = (user % 2 == 1)
          val idx = (user * 1.0 /2).ceil.toInt
          val userKeeps = uriIds.slice((idx-1)*10, idx*10)
          val (selfClicks, otherClicks) = if (selfClicker) (clicks, 0) else (0, clicks)
          if (List(1, 2, 5, 6, 9, 10).toSet.contains(user)) {
            clicksMap.get(Id[User](user)).get === userKeeps.foldLeft(Map.empty[Id[NormalizedURI], (Int, Int)]){(m, uri) => m + (uri -> (selfClicks, otherClicks))}
          } else {
            clicksMap.get(Id[User](user)).get === Map.empty[Id[NormalizedURI], (Int, Int)]
          }
        }
      }
    }

    "compute score" in {
      withDb() { implicit injector =>
        def uriId(x: Int) = Id[NormalizedURI](x)
        def log2(x: Double) = log(x)/log(2)
        val userId = Id[User](1)
        val topic = 1
        val userRelevantBookmarks = Map(1 -> List(uriId(1), uriId(2)), 2 -> List(uriId(3), uriId(4)))
        val allRelevantBookmarks = Map(uriId(1) -> 1, uriId(2) -> 1, uriId(3) -> 2, uriId(4) -> 2, uriId(5) -> 3)
        val userBookmarkClicks = Map(uriId(1) -> (0, 10), uriId(2) -> (15, 20), uriId(3) -> (3, 5), uriId(4) -> (1, 1))
        val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
        rcmder.score(userId, topic, userRelevantBookmarks, allRelevantBookmarks, userBookmarkClicks) === log2(1 + 2 + 0.2 * 15 + 0.8 * 30)
      }
    }

    "correctly compute scores and rank users" in {
       withDb() { implicit injector =>
         val (users, uris) = setup()
         val rcmder = new ExpertRecommender(db, uriTopicRepoA, userBookmarkClicksRepo, bookmarkRepo)
         def userId(i: Int) = users(i).id.get   // clojure
         def uriId(i: Int) = uris(i).id.get
         def log2(x: Double) = log(x)/log(2)
         // 5 hits, 3 from topic 1, 2 from topic 5. relevant users: 1, 2, 9, 10
         val urisAndKeepers = List((uriId(1), List(userId(0), userId(1))),
                               (uriId(2), List(userId(0), userId(1)) ),
                               (uriId(3), List(userId(0), userId(1))),
                               (uriId(45), List(userId(8), userId(9))),
                               (uriId(46), List(userId(8), userId(9)))
                             )
         val ranks = rcmder.rank(urisAndKeepers)
         val experts = ranks.take(4).map{_._1}.map{_.id.toInt}
         experts.toList === List(2, 1, 10, 9)
         // scores for user 1, 2, 9, 10
         val scores = List( log2(1 + 10 + 0.2 * 10 * 10) * 0.6,
                            log2(1 + 10 + 0.8 * 10 * 10) * 0.6,
                            log2(1 + 10 + 0.2 * 10 * 10) * 0.4,
                            log2(1 + 10 + 0.8 * 10 * 10) * 0.4
                              )
         ranks.take(4).map{_._2}.toList === List(scores(1), scores(0), scores(3), scores(2) )
       }
    }

  }

}

trait DbSetupHelper extends ShoeboxTestInjector {
  def setup()(implicit injector: Injector) = {
    val (numUser, numUris, numTopics) = (10, 50, 5)
    val clicks = 10

    val (users, uris) = {
      // 10 users, 50 uris
      val (users, uris) = db.readWrite { implicit s =>
        val users = (1 to numUser).map{ i =>
          userRepo.save(User(firstName = "user%d".format(i), lastName = ""))
        }
        val uris = (1 to numUris).map{ i =>
          uriRepo.save(NormalizedURIFactory(title = "title%d".format(i), url = "http://www.keepit.com/article%d".format(i), state = SCRAPED))
        }
        (users, uris)
      }

      // evenly distribute 50 uris to 5 topics: 1 to 10 have topic 1, 11 to 20 have topic 2, etc
      val helper = new UriTopicHelper
      db.readWrite{ implicit s =>
        val m = numUris / numTopics
        val topicScore = new Array[Double](numTopics)   // all 0, doesn't matter in this test
        (0 until numTopics).foreach{ i =>
          (i*m until (i+1)*m ).foreach{ j =>
            uriTopicRepoA.save(UriTopic(uriId = uris(j).id.get, topic = helper.toByteArray(topicScore, numTopics), primaryTopic = Some(i + 1)))
          }
        }
      }

      // make bookmarks: uri 1 - 10 kept by user 1, 2; uri 11 - 20 kept by user 3, 4; etc
      // thus, user 1, 2 are experts on topic 1, user 3, 4 are experts on topic 2, etc.

      db.readWrite { implicit s =>
        val m = numUris / numTopics
        for( t <- 0 until numTopics){
          val experts = users.slice(2*t, 2*t + 2)
          val keeps = uris.slice(t*m, (t+1)*m)
          for(expert <- experts){
            for(keep <- keeps){
              val url = URLFactory(url = keep.url, normalizedUriId = keep.id.get)
              val url1 = urlRepo.get(keep.url).getOrElse( urlRepo.save(URLFactory(url = keep.url, normalizedUriId = keep.id.get)))
              val bm = BookmarkFactory(
                uri = keep,
                userId = expert.id.get,
                title = keep.title,
                url = url1,
                source = BookmarkSource("test"),
                isPrivate = false,
                kifiInstallation = None)
              bookmarkRepo.save(bm)

              val isSelfClicker = (expert.id.get.id % 2 == 1)         // e.g for topic 1, user 1 self clicks, user 2 receive clicks from others
              (0 until clicks).foreach{ i =>
                userBookmarkClicksRepo.increaseCounts(expert.id.get, keep.id.get, isSelfClicker)
              }
            }
          }
        }
      }
      (users, uris)
    }
    (users, uris)
  }
}