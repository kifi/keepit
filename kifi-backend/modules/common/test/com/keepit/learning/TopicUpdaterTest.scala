package com.keepit.learning

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import com.keepit.test._
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import org.specs2.mutable.Specification
import com.keepit.search.topicModel.TopicModelGlobal



class TopicUpdaterTest extends Specification with TopicUpdaterTestHelper {
  "TopicUpdater" should {
    "correctly update topic tables" in {
      running(new EmptyApplication().withWordTopicModule()) {
        val (users, uris) = setupDB
        val expectedUriToUserEdges = (0 until uris.size).map{ i =>
          (uris(i), List(users(i % uris.size)))
        }.toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)

        val topicUpdater = inject[TopicUpdater]
        topicUpdater.update()
      }
    }
  }
}


trait TopicUpdaterTestHelper extends DbRepos {
  def setupDB = {
    val (numUser, numUri) = (10, TopicModelGlobal.numTopics)
    db.readWrite { implicit s =>
      val users = (0 until numUser).map{ i => userRepo.save(User(firstName = "user%d".format(i), lastName = "" ))}
      val uris = (0 until numUri).map{i  =>
        NormalizedURIFactory(title = "title%d".format(i), url = "http://www.keepit.com/article%d".format(i), state = SCRAPED)
      }
      (users, uris)
    }
  }

  def setupArticleStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d word%d".format(idx, idx)))
      store
    }
  }

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
        id = normalizedUriId,
        title = title,
        description = None,
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

  def mkBookmarks(expectedUriToUserEdges: List[(NormalizedURI, List[User])], mixPrivate: Boolean = false): List[Bookmark] = {
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

