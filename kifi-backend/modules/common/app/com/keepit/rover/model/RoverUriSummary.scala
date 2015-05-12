package com.keepit.rover.model

import com.keepit.commanders.TimeToReadCommander
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ S3ImageConfig, ImageSize, ImagePath }
import com.keepit.model.{ NormalizedURI, URISummary, PageAuthor }
import com.keepit.rover.article.content.EmbedlyMedia
import com.keepit.rover.article.{ EmbedlyArticle, Article, ArticleKind }
import com.kifi.macros.json
import org.joda.time.DateTime
import scala.concurrent.duration._

import scala.concurrent.duration.Duration

@json
case class RoverMedia(mediaType: String, html: String, width: Int, height: Int, url: Option[String])

@json
case class RoverArticleSummary(
    title: Option[String],
    description: Option[String],
    wordCount: Option[Int],
    publishedAt: Option[DateTime],
    authors: Seq[PageAuthor],
    media: Option[RoverMedia]) {

  lazy val readTime: Option[Duration] = wordCount.flatMap(TimeToReadCommander.wordCountToReadTimeMinutes(_).map(_ minutes))

}

object RoverArticleSummary {
  def fromArticle(article: Article): RoverArticleSummary = {
    RoverArticleSummary(
      title = article.content.title,
      description = article.content.description,
      wordCount = getWordCount(article),
      publishedAt = article.content.publishedAt,
      authors = article.content.authors,
      media = getMedia(article)
    )
  }

  private def getWordCount(article: Article): Option[Int] = {
    article.content.content.map(_.split(" ").count(_.nonEmpty))
  }

  private def getMedia(article: Article): Option[RoverMedia] = article match {
    case embedlyArticle: EmbedlyArticle => embedlyArticle.content.media.map(EmbedlyMedia.toRoverMedia)
    case _ => None
  }
}

@json
case class RoverImage(
  path: ImagePath,
  size: ImageSize)

case class RoverUriSummary(article: RoverArticleSummary, imagesByIdealSize: Map[ImageSize, RoverImage]) {
  def toUriSummary()(implicit imageConfig: S3ImageConfig): URISummary = {
    val image = imagesByIdealSize.values.headOption
    URISummary(
      imageUrl = image.map(_.path.getUrl),
      title = article.title,
      description = article.description,
      imageWidth = image.map(_.size.width),
      imageHeight = image.map(_.size.height),
      wordCount = article.wordCount
    )
  }
}

object RoverUriSummary {
  val defaultProvider = EmbedlyArticle
}

case class RoverArticleSummaryKey(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article]) extends Key[RoverArticleSummary] {
  override val version = 1
  val namespace = "best_article_summary_by_uri_id_and_kind"
  def toKey(): String = s"${uriId.id}:${kind.typeCode}"
}

class RoverArticleSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverArticleSummaryKey, RoverArticleSummary](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class RoverArticleImagesKey(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article]) extends Key[Set[RoverImage]] {
  override val version = 1
  val namespace = "images_by_uri_id_and_article_kind"
  def toKey(): String = s"${uriId.id}:${kind.typeCode}"
}

class RoverArticleImagesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverArticleImagesKey, Set[RoverImage]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

