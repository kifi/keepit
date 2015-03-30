package com.keepit.rover.article.content

import com.keepit.model.PageAuthor
import com.keepit.rover.article._
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class LinkedInProfile(
    id: Option[String],
    title: String,
    overview: String,
    sections: String) {
  def content = Seq(title, overview, sections, id.getOrElse("")).filter(_.nonEmpty).mkString("\n")
}

object LinkedInProfile {
  val url = """^https?://([a-z]{2,3})\.linkedin\.com/(?:in/\w+(?:/[a-z]{2,3})?|pub/[\P{M}\p{M}\w]+(?:/\w+){3})(/)?$""".r
}

@json
case class LinkedInProfileContent(
    destinationUrl: String,
    title: Option[String],
    description: Option[String],
    keywords: Seq[String],
    authors: Seq[PageAuthor],
    openGraphType: Option[String],
    publishedAt: Option[DateTime],
    profile: LinkedInProfile,
    http: HttpInfo,
    normalization: NormalizationInfo) extends ArticleContent[LinkedInProfileArticle] with HttpInfoHolder with NormalizationInfoHolder {
  def content = Some(profile.content).filter(_.nonEmpty)
  def mediaType = openGraphType
}
