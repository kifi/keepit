package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.search.Article

class ArticleSerializer extends Format[Article] {

  def writes(article: Article): JsValue =
    JsObject(List(
        "normalizedUriId" -> JsNumber(article.id.id),
        "title" -> JsString(article.title),
        "content" -> JsString(article.content),
        "scrapedAt" -> JsString(article.scrapedAt.toStandardTimeString),
        "httpContentType" -> (article.httpContentType map { t => JsString(t) } getOrElse(JsNull)),
        "httpOriginalContentCharset" -> (article.httpOriginalContentCharset map { t => JsString(t) } getOrElse(JsNull)),
        "state" -> JsString(article.state.toString),
        "message" -> (article.message map { m => JsString(m) } getOrElse(JsNull))
      )
    )

  def reads(json: JsValue): Article = Article(
      Id((json \ "normalizedUriId").as[Long]),
      (json \ "title").as[String],
      (json \ "content").as[String],
      parseStandardTime((json \ "scrapedAt").as[String]),
      (json \ "httpContentType").asOpt[String],
      (json \ "httpOriginalContentCharset").asOpt[String],
      State[NormalizedURI]((json \ "state").as[String]),
      (json \ "message").asOpt[String])
}

object ArticleSerializer {
  implicit val articleSerializer = new ArticleSerializer
}
