package com.keepit.learning.topicmodel

import play.api.test.Helpers._
import com.keepit.test._
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import org.specs2.mutable.Specification
import com.keepit.common.db.slick.Database
import scala.math._
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.google.inject.Injector
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.eliza.FakeElizaServiceClientModule

class TopicUpdaterTest extends Specification with TopicUpdaterTestHelper {
  val numTopics = TopicModelGlobalTest.numTopics

  val topicUpdaterTestModules = Seq(DevTopicModelModule(), ShoeboxFakeStoreModule(), TestActorSystemModule(), FakeElizaServiceClientModule())
  "TopicUpdater" should {
    "correctly update topic tables and be able to reset tables" in {
      running(new ShoeboxApplication(topicUpdaterTestModules:_*)) {
        val (users, uris) = setupDB
        val expectedUriToUserEdges = (0 until uris.size).map{ i =>
          (uris(i), List(users(i % users.size)))
        }.toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)
        val articleStore = setupArticleStore(uris)

        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val uriTopicRepo = inject[UriTopicRepoA]
        val userTopicRepo = inject[UserTopicRepoA]
        val seqInfoRepo = inject[TopicSeqNumInfoRepoA]
        val bmRepo = inject[BookmarkRepo]
        val accessor = inject[SwitchableTopicModelAccessor]
        val factory = inject[TopicModelAccessorFactory]
        val centralConfig = inject[CentralConfig]

        val topicUpdater = new TopicUpdater(db, uriRepo, bmRepo, articleStore, accessor, factory, centralConfig)

        topicUpdater.update()

        val uriTopicHelper = new UriTopicHelper
        db.readOnly { implicit s =>
          uris.zipWithIndex.foreach{ x =>
            val uriTopic = uriTopicRepo.getByUriId(x._1.id.get)
            val arr = new Array[Double](numTopics)
            arr(x._2) = 1.0
            uriTopicHelper.toDoubleArray(uriTopic.get.topic, numTopics) === arr
          }
        }

        val userTopicHelper = new UserTopicByteArrayHelper
        db.readOnly { implicit s =>
          users.zipWithIndex.foreach { x =>
            val userIdx = x._2
            val N = ceil(uris.size *1.0 / users.size).toInt
            val userUris = (0 until N).flatMap{ i => val uriIdx = userIdx + i* users.size ;  if ( uriIdx < uris.size ) Some(uriIdx) else None}
            val topic = new Array[Int](numTopics)
            userUris.foreach( i => topic(i) += 1)
            val userTopic = userTopicRepo.getByUserId(x._1.id.get)
            userTopicHelper.toIntArray(userTopic.get.topic) === topic
          }

        }

        // should be able to reset
        topicUpdater.reset() === (uris.size, users.size)
        topicUpdater.update()

        // after re-update, everything should still be ok.
        db.readOnly { implicit s =>
          uris.zipWithIndex.foreach { x =>
            val uriTopic = uriTopicRepo.getByUriId(x._1.id.get)
            val arr = new Array[Double](numTopics)
            arr(x._2) = 1.0
            uriTopicHelper.toDoubleArray(uriTopic.get.topic, numTopics) === arr
          }
        }

        db.readOnly { implicit s =>
          users.zipWithIndex.foreach { x =>
            val userIdx = x._2
            val N = ceil(uris.size * 1.0 / users.size).toInt
            val userUris = (0 until N).flatMap { i => val uriIdx = userIdx + i * users.size; if (uriIdx < uris.size) Some(uriIdx) else None }
            val topic = new Array[Int](numTopics)
            userUris.foreach(i => topic(i) += 1)
            val userTopic = userTopicRepo.getByUserId(x._1.id.get)
            userTopicHelper.toIntArray(userTopic.get.topic) === topic
          }

        }

      }
    }

    "be able to remodel" in {
      running(new ShoeboxApplication(topicUpdaterTestModules:_*)) {

        val (users, uris) = setupDB
        val expectedUriToUserEdges = (0 until uris.size).map{ i =>
          (uris(i), List(users(i % users.size)))
        }.toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)
        val articleStore = setupArticleStore(uris)

        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val uriTopicRepo = inject[UriTopicRepoA]
        val userTopicRepo = inject[UserTopicRepoA]
        val seqInfoRepo = inject[TopicSeqNumInfoRepoA]
        val bmRepo = inject[BookmarkRepo]
        val accessor = inject[SwitchableTopicModelAccessor]
        val factory = inject[TopicModelAccessorFactory]
        val centralConfig = inject[CentralConfig]

        val topicUpdater = new TopicUpdater(db, uriRepo, bmRepo, articleStore, accessor, factory, centralConfig)
        val topicRemodeler = new TopicRemodeler(db, uriRepo, bmRepo, articleStore, accessor, factory, centralConfig)
        topicUpdater.update()
        topicRemodeler.remodel(continueFromLastInteruption = false)

        val uriTopicRepoB = inject[UriTopicRepoB]
        val userTopicRepoB = inject[UserTopicRepoB]

        val uriTopicHelper = new UriTopicHelper
        db.readOnly { implicit s =>
          uris.zipWithIndex.foreach{ x =>
            val uriTopic = uriTopicRepoB.getByUriId(x._1.id.get)
            val arr = new Array[Double](numTopics)
            arr(x._2) = 1.0
            uriTopicHelper.toDoubleArray(uriTopic.get.topic, numTopics) === arr
          }
        }

        val userTopicHelper = new UserTopicByteArrayHelper
        db.readOnly { implicit s =>
          users.zipWithIndex.foreach { x =>
            val userIdx = x._2
            val N = ceil(uris.size *1.0 / users.size).toInt
            val userUris = (0 until N).flatMap{ i => val uriIdx = userIdx + i* users.size ;  if ( uriIdx < uris.size ) Some(uriIdx) else None}
            val topic = new Array[Int](numTopics)
            userUris.foreach( i => topic(i) += 1)
            val userTopic = userTopicRepoB.getByUserId(x._1.id.get)
            userTopicHelper.toIntArray(userTopic.get.topic) === topic
          }

        }
      }
    }

  }
}


trait TopicUpdaterTestHelper extends ShoeboxApplicationInjector {
  def setupDB(implicit injector: Injector) = {
    val (numUser, numUri) = (10, TopicModelGlobalTest.numTopics)
    db.readWrite { implicit s =>
      val users = (0 until numUser).map{ i => userRepo.save(User(firstName = "user%d".format(i), lastName = "" ))}
      val uris = (0 until numUri).map{i  =>
        uriRepo.save(NormalizedURI.withHash(title = Some("title%d".format(i)), normalizedUrl = "http://www.keepit.com/article%d".format(i), state = SCRAPED))
      }
      (users, uris)
    }
  }

  def setupArticleStore(uris: Seq[NormalizedURI])(implicit injector: Injector) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), content = "content%d word%d".format(idx, idx)))
      store
    }
  }

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String)(implicit injector: Injector) = {
    Article(
        id = normalizedUriId,
        title = title,
        description = None,
        canonicalUrl = None,
        alternateUrls = Set.empty,
        keywords = None,
        media = None,
        content = content,
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        httpOriginalContentCharset = Option("UTF-8"),
        state = SCRAPED,
        message = None,
        titleLang = Some(Lang("en")),
        contentLang = Some(Lang("en")))
  }

  def mkBookmarks(expectedUriToUserEdges: List[(NormalizedURI, List[User])], mixPrivate: Boolean = false)(implicit injector: Injector): List[Bookmark] = {
    db.readWrite { implicit s =>
      expectedUriToUserEdges.flatMap{ case (uri, users) =>
        users.map { user =>
          val url1 = urlRepo.get(uri.url).getOrElse( urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
          bookmarkRepo.save(BookmarkFactory(
            uri = uri,
            userId = user.id.get,
            title = uri.title,
            url = url1,
            source = BookmarkSource("test"),
            isPrivate = mixPrivate && ((uri.id.get.id + user.id.get.id) % 2 == 0),
            kifiInstallation = None))
        }
      }
    }
  }
}

