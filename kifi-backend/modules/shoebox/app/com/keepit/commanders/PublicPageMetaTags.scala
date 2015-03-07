package com.keepit.commanders

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.{ Username, User, Library, URL }
import org.joda.time.DateTime
import com.keepit.common.time.ISO_8601_DAY_FORMAT

import scala.concurrent.duration.Duration

case class LibraryMetadataKey(id: Id[Library]) extends Key[String] {
  override val version = 20
  val namespace = "library_metadata_by_id"
  def toKey(): String = id.id.toString
}

class LibraryMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryMetadataKey, String](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class UserMetadataKey(id: Id[User], tab: UserProfileTab) extends Key[String] {
  override val version = 1
  val namespace = "user_metadata_by_id"
  def toKey(): String = s"${id.id.toString}:${tab.path}"
}

class UserMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserMetadataKey, String](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

/**
 * https://developers.facebook.com/docs/sharing/best-practices
 * og:title – The title of your article, excluding any branding.
 * og:site_name - The name of your website. Not the URL, but the name. (i.e. "IMDb" not "imdb.com".)
 * og:url – This URL serves as the unique identifier for your post. It should match your canonical URL used for SEO, and it should not include any session variables, user identifying parameters, or counters. If you use this improperly, likes and shares will not be aggregated for this URL and will be spread across all of the variations of the URL.
 * og:description – A detailed description of the piece of content, usually between 2 and 4 sentences. This tag is technically optional, but can improve the rate at which links are read and shared.
 * og:image – This is an image associated with your media. We suggest that you use an image of at least 1200x630 pixels.
 * fb:app_id – The unique ID that lets Facebook know the identity of your site. This is crucial for Facebook Insights to work properly. Please see our Insights documentation to learn more.
 * og:type - Different types of media will change how your content shows up in Facebook's newsfeed. There are a number of different common object types already defined. If you don't specify a type, the default will be website. You can also specify your own types via Open Graph.
 *   we'll use article for now
 *   article - Namespace URI: http://ogp.me/ns/article#
 *
 * article:author - This property links to the authors of the article. The target of this can be either a Facebook Profile or a Facebook Page and Facebook will likely offer a chance to follow that author when it's displayed in the news feed. (Note that your authors should have follow enabled so that people can follow them.)
 *
 * article:published_time - datetime - When the article was first published.
 * article:modified_time - datetime - When the article was last changed.
 * article:tag - string array - Tag words associated with this article.
 *
 * profile:first_name - string - A name normally given to an individual by a parent or self-chosen.
 * profile:last_name - string - A name inherited from a family or marriage and by which the individual is commonly known.
 *
 * For twitter meta tags we use https://dev.twitter.com/cards/types/gallery
 * Notes:
 * For twitter:creator we should use the creator twitter handle
 */
trait PublicPageMetaTags {
  def formatOpenGraphForLibrary: String
  def formatOpenGraphForUser: String
}

case class PublicPageMetaPrivateTags(urlPathOnly: String) extends PublicPageMetaTags {

  def formatOpenGraphForLibrary: String = formatOpenGraph

  def formatOpenGraphForUser: String = formatOpenGraph

  private def formatOpenGraph: String =
    s"""
      |<meta name="robots" content="noindex">
      |<meta name="apple-itunes-app" content="app-id=740232575, app-argument=kifi:$urlPathOnly">
      |<meta name="apple-mobile-web-app-capable" content="no">
      |<meta name="google-play-app" content="app-id=com.kifi">
    """.stripMargin
}

case class PublicPageMetaFullTags(unsafeTitle: String, url: String, urlPathOnly: String, unsafeDescription: String,
    images: Seq[String], facebookId: Option[String], createdAt: DateTime, updatedAt: DateTime,
    unsafeFirstName: String, unsafeLastName: String, profileUrl: String, noIndex: Boolean,
    related: Seq[String]) extends PublicPageMetaTags {

  def clean(unsafeString: String) = scala.xml.Utility.escape(unsafeString)

  val title = clean(unsafeTitle)
  val description = clean(unsafeDescription)
  val firstName = clean(unsafeFirstName)
  val lastName = clean(unsafeLastName)
  val fullName = s"$firstName $lastName"

  private def ogSeeAlso: String = related.take(6) map { link =>
    s"""
         |<meta property="og:see_also" content="$link">
       """.stripMargin
  } mkString "\n"

  private def ogImageTags = images map { image =>
    s"""
         |<meta property="og:image" content="$image">
         |<meta itemprop="image" content="$image">
       """.stripMargin.trim
  } mkString "\n"

  private def twitterImageTags = images.headOption map { image =>
    s"""
        |<meta name="twitter:image:src" content="$image">
       """.stripMargin
  } getOrElse ""

  private def facebookIdTag = facebookId.map { id =>
    s"""<meta property="fb:profile_id" content="$id">"""
  } getOrElse ""

  private def noIndexTag = if (noIndex) {
    """
      |<meta name="robots" content="noindex">
    """.stripMargin
  } else ""

  def formatOpenGraphForLibrary: String = {
    formatOpenGraph(s"${PublicPageMetaTags.facebookNameSpace}:library") +
      s"""
      |<meta name="author" content="$firstName $lastName">
      |<meta property="${PublicPageMetaTags.facebookNameSpace}:owner" content="$profileUrl">
      |<meta property="og:updated_time" content="${ISO_8601_DAY_FORMAT.print(updatedAt)}">
      |$ogSeeAlso
     """.stripMargin
  }

  def formatOpenGraphForUser: String = {
    formatOpenGraph("profile") +
      s"""
      |<meta property="profile:first_name" content="$firstName">
      |<meta property="profile:last_name" content="$lastName">
      |$facebookIdTag
     """.stripMargin
  }

  //  verify with https://developers.facebook.com/tools/debug/og/object/
  private def formatOpenGraph(ogType: String): String = {

    s"""
      |<title>$title</title>
      |<meta name="apple-itunes-app" content="app-id=740232575, app-argument=kifi:$urlPathOnly">
      |<meta name="apple-mobile-web-app-capable" content="no">
      |<meta name="google-play-app" content="app-id=com.kifi">
      |<meta property="og:description" content="$description">
      |<meta property="og:title" content="$title">
      |<meta property="og:type" content="$ogType">
      |<meta property="al:iphone:url" content="kifi:/$urlPathOnly">
      |<meta property="al:iphone:app_store_id" content="740232575">
      |<meta property="al:iphone:app_name" content="Kifi iPhone App">
      |<meta property="al:android:url" content="kifi:/$urlPathOnly">
      |<meta property="al:android:package" content="com.kifi">
      |<meta property="al:android:app_name" content="Kifi Android App">
      |<meta property="al:android:class" content="com.kifi.SplashActivity">
      |$ogImageTags
      |<meta property="og:url" content="$url">
      |<meta property="og:site_name" content="Kifi - Connecting People With Knowledge">
      |<meta property="fb:app_id" content="${PublicPageMetaTags.facebookAppId}">
      |<meta name="description" content="$description">
      |<link rel="canonical" href="$url">
      |<meta name="twitter:card" content="summary_large_image">
      |<meta name="twitter:site" content="@kifi">
      |<meta name="twitter:creator" content="@kifi">
      |<meta name="twitter:title" content="$title">
      |<meta name="twitter:description" content="$description">
      |<meta name="twitter:url" content="$url">
      |<meta name="twitter:app:name:iphone" content="Kifi iPhone App">
      |<meta name="twitter:app:id:iphone" content="740232575">
      |<meta name="twitter:app:url:iphone" content="kifi://www.kifi.com$urlPathOnly">
      |<meta name="twitter:app:name:googleplay" content="Kifi Android App">
      |<meta name="twitter:app:id:googleplay" content="com.kifi">
      |<meta name="twitter:app:url:googleplay" content="kifi:/$urlPathOnly">
      |$twitterImageTags
      |<meta name="twitter:dnt" content="on">
      |<meta itemprop="name" content="$title">
      |<meta itemprop="description" content="$description">
      |$noIndexTag
    """.stripMargin
  }
}

object PublicPageMetaTags {
  val siteName = "Kifi"
  val facebookAppId = "104629159695560"
  val facebookNameSpace = "fortytwoinc"
  /**
   * http://www.swellpath.com/2014/05/update-new-title-tag-meta-description-character-lengths/
   * Magic numbers:
   * Google trimms descriptions with more then 115 characters
   * Google does not like descriptions with less then 60 characters
   * This function adds a bit of diversity to the description tags and tries to keep them longer then 70 characters.
   */
  def generateLibraryMetaTagDescription(description: Option[String], ownerName: String, libraryName: String, altDesc: Option[String]): String = {
    val base = description match {
      case None =>
        s"$ownerName's $libraryName Library"
      case Some(desc) =>
        val cleaned = trim(desc)
        if (cleaned.size > 70) cleaned
        else s"$ownerName's $libraryName Library: $cleaned"
    }
    if (base.size > 70) base
    else {
      val withAltDesc = altDesc.map(desc => s"${trim(base)}. $desc").getOrElse(base)
      if (withAltDesc.size > 70) withAltDesc
      else {
        val extended = s"$withAltDesc. Kifi -- Connecting people with knowledge"
        if (extended.size > 80) extended
        else s"$withAltDesc. Kifi -- the smartest way to collect, discover, and share knowledge"
      }
    }
  }

  private def trim(str: String): String = {
    val trimmed = str.trim
    if (trimmed.endsWith(".")) trimmed.substring(0, trimmed.length - 1)
    else trimmed
  }
}
