package com.keepit.rover.sensitivity

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ EmbedlyArticle, Article }
import com.keepit.rover.commanders.ArticleCommander
import com.keepit.search.{ LangDetector, Lang }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import com.keepit.common.core._

@Singleton
class RoverSensitivityCommander @Inject() (
    uriSensitivityCache: UriSensitivityCache,
    pornDetector: PornDetectorFactory,
    articleCommander: ArticleCommander,
    private implicit val executionContext: ExecutionContext) {

  def areSensitive(uriIds: Set[Id[NormalizedURI]], getContent: Set[Id[NormalizedURI]] => Map[Id[NormalizedURI], Future[Set[Article]]] = articleCommander.getBestArticleFuturesByUris): Future[Map[Id[NormalizedURI], Option[Boolean]]] = {
    val keys = uriIds.map(UriSensitivityKey.apply)
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    uriSensitivityCache.bulkGetOrElseFutureOpt(keys) { missingKeys =>
      val futureSensitivityByUri = getContent(missingKeys.map(_.uriId)).map {
        case (uriId, futureContent) =>
          futureContent.map {
            case content =>
              val sensitivity = if (content.isEmpty) None else Some(isSensitive(content))
              UriSensitivityKey(uriId) -> sensitivity
          }
      }
      Future.sequence(futureSensitivityByUri).imap(_.toMap)
    }.imap { _.map { case (key, sensitivity) => key.uriId -> sensitivity } }
  }

  def isSensitive(article: Article): Boolean = isSensitive(Set(article))

  def isSensitive(content: Set[Article]): Boolean = {
    val isSensitiveForEmbedly = content.exists {
      case embedlyArticle: EmbedlyArticle => embedlyArticle.content.isSafe.exists(!_)
      case _ => false
    }
    lazy val isPornContent = content.exists(isPorn)
    isSensitiveForEmbedly || isPornContent
  }

  private def isPorn(article: Article): Boolean = {
    val isPornDomain = PornDomains.isPornDomain(article.url) || PornDomains.isPornDomain(article.content.destinationUrl)
    lazy val isPornContent = {
      val text = (article.content.title ++ article.content.description ++ article.content.content.map(_.take(100000))).mkString(" ")
      val lang = LangDetector.detect(text)
      lang == Lang("en") && text.size > 100 && pornDetector.slidingWindow().isPorn(text)
    }
    isPornDomain || isPornContent
  }
}

case class UriSensitivityKey(uriId: Id[NormalizedURI]) extends Key[Boolean] {
  override val version = 1
  val namespace = "sensitivity_by_uri_id"
  def toKey(): String = uriId.id.toString
}

class UriSensitivityCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UriSensitivityKey, Boolean](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)