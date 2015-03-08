package com.keepit.rover.article

import com.keepit.model.PageAuthor
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class LinkedInProfile(
    id: String,
    title: String,
    overview: String,
    sections: String) {
  def content = Seq(title, overview, sections, id).filter(_.nonEmpty).mkString("\n")
}

@json
case class LinkedInProfileContent(
    destinationUrl: String,
    title: Option[String],
    description: Option[String],
    keywords: Seq[String],
    authors: Seq[PageAuthor],
    mediaType: Option[String],
    publishedAt: Option[DateTime],
    profile: LinkedInProfile,
    http: HttpInfo,
    normalization: NormalizationInfo) extends ArticleContent with HttpInfoHolder with NormalizationInfoHolder {
  def content = Some(profile.content)
}
