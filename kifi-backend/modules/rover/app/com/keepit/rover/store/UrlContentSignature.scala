package com.keepit.rover.store

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.time._
import com.keepit.model.UrlHash
import com.keepit.rover.article.content.ArticleContent
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.document.utils.Signature
import com.kifi.macros.json
import org.joda.time.DateTime
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

@json
case class UrlContentSignature(signature: Option[Signature], computedAt: DateTime = currentDateTime)

case class UrlContentSignatureKey(destinationUrl: String, kind: ArticleKind[_ <: Article]) extends Key[UrlContentSignature] {
  override val version = 1
  val namespace = "content_signature_by_url_and_article_kind"
  def toKey(): String = s"${UrlHash.hashUrl(destinationUrl).hash}:${kind.typeCode}"
}

object UrlContentSignatureKey {
  def fromArticle(article: Article): UrlContentSignatureKey = {
    UrlContentSignatureKey(article.content.destinationUrl, article.kind)
  }
}

class UrlContentSignatureCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[UrlContentSignatureKey, UrlContentSignature](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*) {
}

@Singleton
class ContentSignatureCommander @Inject() (
    cache: UrlContentSignatureCache,
    private implicit val executionContext: ExecutionContext) {

  private val consolidate = new RequestConsolidator[Article, Signature](5 minutes)

  def computeArticleSignature(latestArticle: Article): Future[Signature] = consolidate(latestArticle) { article =>
    SafeFuture {
      ArticleContent.defaultSignature(article.content) tap { articleSignature =>
        cache.direct.set(UrlContentSignatureKey.fromArticle(article), UrlContentSignature(Some(articleSignature)))
      }
    }
  }

  def computeUrlSignature[A <: Article](url: String, fetchedArticle: Option[A])(implicit kind: ArticleKind[A]): Future[Option[Signature]] = {
    if (fetchedArticle.exists(_.url != url)) {
      throw new IllegalArgumentException(s"Article was not fetched from argument url: $url (fetched instead from ${fetchedArticle.get.url})")
    }
    fetchedArticle match {
      case Some(article) if article.content.destinationUrl == url => computeArticleSignature(article).imap(Some(_)) // do not follow redirects
      case _ => {
        cache.direct.set(UrlContentSignatureKey(url, kind), UrlContentSignature(None))
        Future.successful(None)
      }
    }
  }

  def getCachedUrlSignature[A <: Article](url: String)(implicit kind: ArticleKind[A]): Option[UrlContentSignature] = {
    cache.direct.get(UrlContentSignatureKey(url, kind))
  }

}

