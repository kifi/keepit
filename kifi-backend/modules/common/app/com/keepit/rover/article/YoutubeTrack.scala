package com.keepit.rover.article

import com.keepit.search.Lang
import com.kifi.macros.json

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
case class YoutubeTrack(content: String, info: YoutubeTrackInfo)
