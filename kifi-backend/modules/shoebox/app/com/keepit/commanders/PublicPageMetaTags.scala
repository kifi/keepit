package com.keepit.commanders

import org.joda.time.DateTime
import com.keepit.common.time.ISO_8601_DAY_FORMAT

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
  def formatOpenGraph: String
}

case class PublicPageMetaPrivateTags(urlPathOnly: String) extends PublicPageMetaTags {
  def formatOpenGraph: String =
    s"""
      |<meta name="robots" content="noindex">
      |<meta name="apple-itunes-app" content="app-id=740232575, app-argument=kifi:$urlPathOnly"/>
      |<meta name="apple-mobile-web-app-capable" content="no"/>
    """.stripMargin

}

case class PublicPageMetaFullTags(unsafeTitle: String, url: String, urlPathOnly: String, unsafeDescription: String, images: Seq[String], facebookId: Option[String],
    createdAt: DateTime, updatedAt: DateTime, unsafeTags: Seq[String], unsafeFirstName: String, unsafeLastName: String) extends PublicPageMetaTags {

  def clean(unsafeString: String) = scala.xml.Utility.escape(unsafeString)

  val title = clean(unsafeTitle)
  val description = clean(unsafeDescription)
  val tags = unsafeTags.distinct.filter(_.length > 1).take(100).map(clean)
  val firstName = clean(unsafeFirstName)
  val lastName = clean(unsafeLastName)

  def tagList = tags.mkString(",")

  def formatOpenGraph: String = {

    def ogImageTags = images map { image =>
      s"""
         |<meta property="og:image" content="$image" />
         |<meta itemprop="image" content="$image">
       """.stripMargin
    } mkString ("\n")

    def twitterImageTags = images.headOption map { image =>
      s"""
        |<meta name="twitter:image:src" content="$image">
       """.stripMargin
    } getOrElse ("")

    def facebookIdTag = facebookId.map { id =>
      s"""<meta property="article:author" content="$id"/>"""
    } getOrElse ""

    s"""
      |<html itemscope itemtype="http://schema.org/Product">
      |<title>${title}</title>
      |<meta name="apple-itunes-app" content="app-id=740232575, app-argument=kifi:$urlPathOnly"/>
      |<meta name="apple-mobile-web-app-capable" content="no"/>
      |<meta property="og:description" content="${description}" />
      |<meta property="og:title" content="${title}" />
      |<meta property="og:type" content="blog" />
      |<meta property="og:published_time" content="${ISO_8601_DAY_FORMAT.print(createdAt)}" />
      |<meta property="og:modified_time" content="${ISO_8601_DAY_FORMAT.print(updatedAt)}" />
      |$ogImageTags
      |$facebookIdTag
      |<meta property="og:url" content="$url" />
      |<meta property="og:site_name" content="Kifi - Connecting People With Knowledge" />
      |<meta property="fb:app_id" content="${PublicPageMetaTags.appId}" />
      |<meta property="fb:first_name" content="${firstName}" />
      |<meta property="fb:last_name" content="${lastName}" />
      |<meta property="fb:tag" content="$tagList" />
      |<meta property="fb:admins" content="646386018,71105121,7800404,1343280666,1367777495,575533310,636721190" />
      |<meta name="description" content="$description">
      |<meta name="keywords" content="$tagList">
      |<meta name="author" content="${firstName} ${lastName}">
      |<link rel="canonical" href="$url" />
      |<meta name="twitter:card" content="summary_large_image" />
      |<meta name="twitter:site" content="@kifi" />
      |<meta name="twitter:creator" content="@kifi" />
      |<meta name="twitter:title" content="${title}">
      |<meta name="twitter:description" content="${description}">
      |<meta name="twitter:url" content="$url" />
      |<meta name="twitter:app:name:iphone" content="Kifi Iphone App">
      |<meta name="twitter:app:id:iphone" content="740232575">
      |<meta name="twitter:app:url:iphone" content="kifi:/$urlPathOnly">
      |<meta name="twitter:app:name:googleplay" content="Kifi Android App">
      |<meta name="twitter:app:id:googleplay" content="com.kifi">
      |<meta name="twitter:app:url:googleplay" content="kifi:/$urlPathOnly">
      |$twitterImageTags
      |<meta itemprop="name" content="$title">
      |<meta itemprop="description" content="$description">
    """.stripMargin
  }
}

object PublicPageMetaTags {
  val siteName = "Kifi"
  val appId = "104629159695560"
}