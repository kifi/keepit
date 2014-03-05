package com.keepit.search

import org.specs2.mutable._
import play.api.libs.json.Json
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.NormalizedURIStates._
import org.joda.time.DateTimeZone

class ArticleTest extends Specification {

  "Article" should {
    "be serialized" in {
      val article = Article(
        id = Id(22),
        title = "my title",
        description = Some("my description"),
        canonicalUrl = Some("canonical url"),
        alternateUrls = Set.empty,
        keywords = Some("my keyword"),
        media = Some("my media"),
        content = "my content",
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        httpOriginalContentCharset = Option("UTF-8"),
        state = SCRAPED,
        message = Some("everything is good"),
        titleLang = Some(Lang("en")),
        contentLang = Some(Lang("en"))
      )
      println(implicitly[DateTimeZone])
      val json = Json.toJson(article)
      println(json)
      val newArticle = json.as[Article]
      article === newArticle
    }

    "be serialized with new lines and escapable stuff" in {
      val article = Article(
        id = Id(22),
        title = "my title",
        description = None,
        canonicalUrl = None,
        alternateUrls = Set.empty,
        keywords = None,
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
        contentLang = None
      )
      val json = Json.toJson(article)
      println(json)
      val newArticle = json.as[Article]
      article === newArticle
    }

    "be compatible with old Article" in {
      val now = currentDateTime
      val article = Article(
        id = Id(22),
        title = "my title",
        description = None,
        canonicalUrl = None,
        alternateUrls = Set.empty,
        keywords = None,
        media = None,
        content = "my content",
        scrapedAt = now,
        httpContentType = None,
        httpOriginalContentCharset = None,
        state = SCRAPED,
        message = None,
        titleLang = None,
        contentLang = None
      )

      val json = Json.parse(s"""
        {
          "normalizedUriId":22,
          "title":"my title",
          "content":"my content",
          "scrapedAt":"${now.toStandardTimeString}",
          "state":"scraped"
        }"""
      )
      val newArticle = json.as[Article]

      newArticle === article
    }
  }

}
