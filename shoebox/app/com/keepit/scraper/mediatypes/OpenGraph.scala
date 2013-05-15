package com.keepit.scraper.mediatypes

object OpenGraph extends MediaTypes {

  val typeMetadata = "og:type"

  // object types to string
  private[this] val objTypeMap: Map[String, Option[String]] = Map(
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
  def toMediaTypeString(t: String): Option[String] = {
    objTypeMap.getOrElse(t, Some(punctPattern.replaceAllIn(t, " ")))
  }
}