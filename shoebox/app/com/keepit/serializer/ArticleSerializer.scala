package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.time._
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.search.Article

class ArticleSerializer extends Writes[Article] with Reads[Article] {
  
  def writes(article: Article): JsValue =
    JsObject(List(
        "normalizedUriId" -> JsNumber(article.normalizedUriId.id),
        "title" -> JsString(article.title),
        "content" -> JsString(article.content)
      )
    )
    
  def reads(json: JsValue): Article = Article(
      Id((json \ "normalizedUriId").as[Long]), 
      (json \ "title").as[String], 
      (json \ "content").as[String])
}

object ArticleSerializer {
  implicit val articleSerializer = new ArticleSerializer
}
