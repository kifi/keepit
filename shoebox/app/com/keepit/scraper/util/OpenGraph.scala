package com.keepit.scraper.util

import com.keepit.scraper.extractor.Extractor

object OpenGraph {
  // object types to string
  private[this] val ogObjTypeMap: Map[String, Option[String]] = Map(
    "video.movie" -> Some("video movie"),
    "video.episode" -> Some("video episode"),
    "video.tv_show" -> Some("video tv show"),
    "video.other" -> Some("video"),
    "video" -> Some("video"),
    "movie" -> Some("video movie"),
    "music.song" -> Some("music song"),
    "music.album" -> Some("music album"),
    "music.playlist" -> Some("music playlist"),
    "music.radio_station" -> Some("music radio station"),
    "book" -> Some("book"),

    // too generic
    "article" -> None,
    "profile" -> None
  )
  private[this] val punctPattern = """\p{Punct}""".r

  private[this] def toMediaTypeString(t: String): Option[String] = {
    ogObjTypeMap.getOrElse(t, Some(punctPattern.replaceAllIn(t, " ")))
  }

  def getMediaTypeString(x: Extractor): Option[String] = {
    // extract open graph object type
    x.getMetadata("og:type").flatMap(toMediaTypeString(_))
  }
}