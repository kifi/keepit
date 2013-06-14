package com.keepit.serializer

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.search.Article
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.Lang

class ArticleSerializerTest extends Specification {

  "ArticleSerializer" should {
    "do a basic serialization flow" in {
      val article = Article(
          id = Id(22),
          title = "my title",
          description = Some("my description"),
          media = Some("my media"),
          content = "my content",
          scrapedAt = currentDateTime,
          httpContentType = Some("text/html"),
          httpOriginalContentCharset = Option("UTF-8"),
          state = SCRAPED,
          message = Some("everything is good"),
          titleLang = Some(Lang("en")),
          contentLang = Some(Lang("en")))
      val serializer = new ArticleSerializer()
      val json = serializer.writes(article)
      println(json)
      val newArticle = serializer.reads(json).get
      article === newArticle
    }

    "serialization of new lines and escapable stuff" in {
      val article = Article(
          id = Id(22),
          title = "my title",
          description = None,
          media = None,
          content = """my content
          has few lines in it
          and "some qoutes"
          and othercaracters like: \n \t and ':-)'
          """,
          scrapedAt = currentDateTime,
          httpContentType = None,
          httpOriginalContentCharset = None,
          state = SCRAPED,
          message = None,
          titleLang = None,
          contentLang = None)
      val serializer = new ArticleSerializer()
      val json = serializer.writes(article)
      println(json)
      val newArticle = serializer.reads(json).get
      article === newArticle
    }
  }

}
