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
 * we'll do author later
 * article:author - This property links to the authors of the article. The target of this can be either a Facebook Profile or a Facebook Page and Facebook will likely offer a chance to follow that author when it's displayed in the news feed. (Note that your authors should have follow enabled so that people can follow them.)
 *
 * article:published_time - datetime - When the article was first published.
 * article:modified_time - datetime - When the article was last changed.
 * article:tag - string array - Tag words associated with this article.
 *
 * profile:first_name - string - A name normally given to an individual by a parent or self-chosen.
 * profile:last_name - string - A name inherited from a family or marriage and by which the individual is commonly known.
 */
case class PublicPageMetaTags(title: String, url: String, description: String, images: Seq[String],
    createdAt: DateTime, updatedAt: DateTime, tags: Seq[String], firstName: String, lastName: String) {

  val tagList = tags.mkString(",")

  def formatOpenGraph: String = {
    val imageTags = images map { image =>
      s"""<meta property="og:image" content="$image" />"""
    } mkString ("\n")

    s"""
      |<title>${title} \u2022 ${firstName} ${lastName} \u2022 Kifi</title>
      |<meta property="og:description" content="${description}" />
      |<meta property="og:title" content="${title}" />
      |<meta property="og:type" content="blog" />
      |<meta property="og:published_time" content="${ISO_8601_DAY_FORMAT.print(createdAt)}" />
      |<meta property="og:modified_time" content="${ISO_8601_DAY_FORMAT.print(updatedAt)}" />
      |$imageTags
      |<meta property="og:url" content="$url" />
      |<meta property="og:site_name" content="Kifi - Connecting People With Knowledge" />
      |<meta property="fb:app_id" content="${PublicPageMetaTags.appId}" />
      |<meta property="fb:first_name" content="${firstName}" />
      |<meta property="fb:last_name" content="${lastName}" />
      |<meta property="fb:tag" content="$tagList" />
      |<meta name="description" content="$description">
      |<meta name="keywords" content="$tagList">
      |<meta name="author" content="${firstName} ${lastName}">
      |<link rel="canonical" href="$url" />
    """.stripMargin
  }
}

object PublicPageMetaTags {
  val siteName = "Kifi"
  val appId = "104629159695560"
}