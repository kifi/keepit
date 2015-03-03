package com.keepit.rover.article

import com.keepit.model.PageAuthor
import com.keepit.search.Lang
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class YoutubeTrackInfo(
    id: Int,
    name: String,
    langCode: Lang,
    langOriginal: String,
    langTranslated: String,
    isDefault: Boolean,
    kind: Option[String]) {
  def isAutomatic = (kind == Some("asr"))
}

@json
case class YoutubeTrack(info: YoutubeTrackInfo, content: String)

@json
case class YoutubeVideo(
  title: Option[String],
  description: String,
  keywords: Seq[String],
  channel: String,
  tracks: Seq[YoutubeTrack],
  viewCount: Int)

@json
case class YoutubeContent(
    destinationUrl: String,
    title: Option[String],
    description: Option[String],
    keywords: Seq[String],
    authors: Seq[PageAuthor],
    publishedAt: Option[DateTime],
    http: HTTPContext,
    normalization: NormalizationContext,
    video: YoutubeVideo) extends ArticleContent with HTTPContextHolder with NormalizationContextHolder {

  def content = Some(Seq(
    video.title.getOrElse(""),
    video.description,
    video.channel,
    preferredTrack.map(_.content).getOrElse("")
  ).filter(_.nonEmpty).mkString("\n")).filter(_.nonEmpty)

  private def preferredTrack: Option[YoutubeTrack] = {
    video.tracks.find(_.info.isDefault) orElse {
      video.tracks.find(_.info.isAutomatic).map(asr =>
        video.tracks.find(t => t.info.langCode == asr.info.langCode && !t.info.isAutomatic).getOrElse(asr)
      )
    } orElse video.tracks.find(_.info.langCode.lang == "en")
  }
}
