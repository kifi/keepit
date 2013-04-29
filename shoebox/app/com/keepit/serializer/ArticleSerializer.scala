package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import com.keepit.search.Lang
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.search.Article
import org.joda.time.DateTime

class ArticleSerializer extends Format[Article] {

  def writes(article: Article): JsValue =
    JsObject(List(
        "normalizedUriId" -> JsNumber(article.id.id),
        "title" -> JsString(article.title),
        "content" -> JsString(article.content),
        "description" -> (article.description map { m => JsString(m) } getOrElse(JsNull)),
        "scrapedAt" -> Json.toJson(article.scrapedAt),
        "httpContentType" -> (article.httpContentType map { t => JsString(t) } getOrElse(JsNull)),
        "httpOriginalContentCharset" -> (article.httpOriginalContentCharset map { t => JsString(t) } getOrElse(JsNull)),
        "state" -> JsString(article.state.toString),
        "message" -> (article.message map { m => JsString(m) } getOrElse(JsNull)),
        "titleLang" -> (article.titleLang map { lang => JsString(lang.lang) } getOrElse(JsNull)),
        "contentLang" -> (article.contentLang map { lang => JsString(lang.lang) } getOrElse(JsNull)),
        "destinationUrl" -> (article.destinationUrl map (JsString(_)) getOrElse(JsNull))
      )
    )

  def reads(json: JsValue): JsResult[Article] = JsSuccess(Article(
      Id((json \ "normalizedUriId").as[Long]),
      (json \ "title").as[String],
      (json \ "content").as[String],
      (json \ "description").asOpt[String],
      (json \ "scrapedAt").as[DateTime],
      (json \ "httpContentType").asOpt[String],
      (json \ "httpOriginalContentCharset").asOpt[String],
      State[NormalizedURI]((json \ "state").as[String]),
      (json \ "message").asOpt[String],
      (json \ "titleLang").asOpt[String] map { lang => Lang(lang) },
      (json \ "contentLang").asOpt[String] map { lang => Lang(lang) },
      (json \ "destinationUrl").asOpt[String]
    ))
}

object ArticleSerializer {
  implicit val articleSerializer = new ArticleSerializer
}
