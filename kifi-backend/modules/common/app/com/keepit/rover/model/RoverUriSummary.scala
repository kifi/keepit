package com.keepit.rover.model

import com.keepit.commanders.{ ProcessedImageSize, TimeToReadCommander }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ S3ImageConfig, ImageSize, ImagePath }
import com.keepit.model._
import com.keepit.rover.article.content.EmbedlyMedia
import com.keepit.rover.article.{ EmbedlyArticle, Article, ArticleKind }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ JsValue, Format, Json }
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

  val empty = RoverArticleSummary(None, None, None, None, Seq.empty, None)
}

@json
case class BasicImage(
  sourceImageHash: ImageHash,
  path: ImagePath,
  size: ImageSize)

object BasicImage {
  implicit def fromBaseImage(image: BaseImage): BasicImage = {
    BasicImage(image.sourceFileHash, image.imagePath, image.imageSize)
  }
}

case class BasicImages(images: Set[BasicImage]) {
  def get(idealSize: ImageSize, strictAspectRatio: Boolean = false): Option[BasicImage] = {
    ProcessedImageSize.pickByIdealImageSize(idealSize, images, strictAspectRatio)(_.size)
  }
}

object BasicImages {
  val empty = BasicImages(Set.empty)
  implicit val format: Format[BasicImages] = new Format[BasicImages] {
    def reads(json: JsValue) = json.validate[Set[BasicImage]].map(BasicImages(_))
    def writes(images: BasicImages) = Json.toJson(images.images)
  }
}

case class RoverUriSummary(article: RoverArticleSummary, images: BasicImages) {
  def toUriSummary(idealImageSize: ImageSize)(implicit imageConfig: S3ImageConfig): URISummary = {
    val image = images.get(idealImageSize)
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
  val empty = RoverUriSummary(RoverArticleSummary.empty, BasicImages.empty)
}

case class RoverArticleSummaryKey(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article]) extends Key[RoverArticleSummary] {
  override val version = 1
  val namespace = "best_article_summary_by_uri_id_and_kind"
  def toKey(): String = s"${uriId.id}:${kind.typeCode}"
}

class RoverArticleSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverArticleSummaryKey, RoverArticleSummary](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class RoverArticleImagesKey(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article]) extends Key[BasicImages] {
  override val version = 2
  val namespace = "images_by_uri_id_and_article_kind"
  def toKey(): String = s"${uriId.id}:${kind.typeCode}"
}

class RoverArticleImagesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverArticleImagesKey, BasicImages](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

