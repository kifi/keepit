package com.keepit.serializer

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.search.Article
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._

@RunWith(classOf[JUnitRunner])
class ArticleSerializerTest extends SpecificationWithJUnit {

  "ArticleSerializer" should {
    "do a basic serialization flow" in {
      val article = Article(
          id = Id(22),
          title = "my title",
          content = "my content",
          scrapedAt = currentDateTime,
          httpContentType = Some("text/html"),
          state = SCRAPED,
          message = Some("everything is good"))
      val serializer = new ArticleSerializer()
      val json = serializer.writes(article)
      println(json)
      val newArticle = serializer.reads(json)
      article === newArticle
    }

    "serialization of new lines and escapable stuff" in {
      val article = Article(
          id = Id(22),
          title = "my title",
          content = """my content
          has few lines in it
          and "some qoutes"
          and othercaracters like: \n \t and ':-)'
          """,
          scrapedAt = currentDateTime,
          httpContentType = None,
          state = SCRAPED,
          message = None)
      val serializer = new ArticleSerializer()
      val json = serializer.writes(article)
      println(json)
      val newArticle = serializer.reads(json)
      article === newArticle
    }
  }

}