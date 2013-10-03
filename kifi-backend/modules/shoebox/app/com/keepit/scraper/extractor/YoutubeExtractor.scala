package com.keepit.scraper.extractor

import com.keepit.scraper.{HttpFetcher, Scraper}
import com.keepit.common.net.{Host, URI}
import com.keepit.search.Lang
import org.jsoup.nodes.{Element, Document}
import scala.collection.JavaConversions._
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.regex.Pattern

case class YoutubeExtractorProvider(httpFetcher: HttpFetcher) extends ExtractorProvider {
  def isDefinedAt(uri: URI) = {
    uri match {
      case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) =>
        path.endsWith("/watch") && query.containsParam("v")
      case _ => false
    }
  }
  def apply(uri: URI) = new YoutubeExtractor(uri.toString, Scraper.maxContentChars, httpFetcher)
}

class YoutubeExtractor(url: String, maxContentChars: Int, httpFetcher: HttpFetcher) extends JsoupBasedExtractor(url, maxContentChars) {

  def parse(doc: Document): String = {
    val headline = doc.getElementById("watch-headline-title").text
    val description = doc.select("#watch-description-text, #watch-description-extras div.content, #watch-description-extra-info").text
    val channel = doc.select("#watch7-user-header .yt-user-name").text()
    val closedCaptions = getClosedCaptions(doc).getOrElse("")

    Seq(headline, description, channel, closedCaptions).filter(_.nonEmpty).mkString("\n")
  }

  private def getClosedCaptions(doc: Document): Option[String] = {
    for {
      ttsUrl <- findTTSUrl(doc)
      ttsUri <- URI.safelyParse(ttsUrl)
      ttsParameters <- ttsUri.query.map(q => Seq("key", "expire", "sparams", "signature", "caps", "v", "asr_langs").flatMap(q.getParam).mkString("&"))
      track <- findTrack(ttsParameters)
    } yield getTrack(track, ttsParameters)
  }

  private def findTTSUrl(doc: Document): Option[String] = {
    val ttsUrlPattern = """(?:'TTS_URL'|"ttsurl): "(http.*)",""".r
    val script = doc.getElementsByTag("script").toString
    ttsUrlPattern.findFirstIn(script).map { case ttsUrlPattern(url) => clean(url, "\\/" -> "/", "\\u0026" -> "&") }
  }

  private def findTrack(ttsParameters: String): Option[YoutubeTrack] = {
    val trackListUrl = "https://www.youtube.com/api/timedtext?asrs=1&type=list&tlangs=1&" + ttsParameters
    val trackListExtractor = new YoutubeTrackListExtractor(trackListUrl)
    httpFetcher.fetch(trackListUrl)(trackListExtractor.process)
    val tracks = trackListExtractor.getTracks()
    tracks.find(_.isDefault) orElse {
      tracks.find(_.isAutomatic).map(asr =>
        tracks.find(t => t.langCode == asr.langCode && !t.isAutomatic).getOrElse(asr)
      )
    } orElse tracks.find(_.langCode.lang == "en")
  }

  private def getTrack(track: YoutubeTrack, ttsParameters: String): String = {
    def parameter(name: String, value: String) = s"$name=${URLEncoder.encode(value, "UTF-8")}"
    val trackUrl = Seq(
      "https://www.youtube.com/api/timedtext?type=track&",
      ttsParameters,
      parameter("&name", track.name),
      parameter("&lang", track.langCode.lang),
      track.kind.map(parameter("&kind", _)).getOrElse("")
    ).mkString
    val trackExtractor = new JsoupBasedExtractor(trackUrl, maxContentChars) {
      def parse(doc: Document): String = clean(doc.getElementsByTag("text").map(_.text).mkString(" "), "&quot;" -> "\"", "&amp;" -> "&", "&#39;" -> "'", "&lt;"-> "<", "&gt;" -> ">")
    }
    httpFetcher.fetch(trackUrl)(trackExtractor.process)
    log.info(s"Fetched ${(if (track.isDefault) "default " else "") + (if (track.isAutomatic) "automatic " else "") + track.langTranslated} closed captions for ${url}")
    trackExtractor.getContent()
  }

  private def clean(text: String, substitutions: (String, String)*) = {
    val replacement = substitutions.toMap.withDefault(identity)
    val regex = replacement.keysIterator.map(Pattern.quote).mkString("|").r
    regex.replaceAllIn(text, m => replacement(m.matched))
  }
}

class YoutubeTrackListExtractor(trackListUrl: String) extends JsoupBasedExtractor(trackListUrl, Int.MaxValue, Some(Parser.xmlParser())) {
  def parse(doc: Document): String = doc.getElementsByTag("track").toString
  def getTracks(): Seq[YoutubeTrack] = doc.getElementsByTag("track").map(YoutubeTrack.parse)
}

case class YoutubeTrack(id: Int, name: String, langCode: Lang, langOriginal: String, langTranslated: String, isDefault: Boolean, kind: Option[String]) {
  def isAutomatic = (kind == Some("asr"))
}

object YoutubeTrack {
  def parse(track: Element): YoutubeTrack = YoutubeTrack(
    id = track.attr("id").toInt,
    name = track.attr("name"),
    langCode = Lang(track.attr("lang_code")),
    langOriginal = track.attr("lang_original"),
    langTranslated = track.attr("lang_translated"),
    isDefault = track.hasAttr("lang_default"),
    kind = if (track.hasAttr("kind")) Some(track.attr("kind")) else None
  )
}
