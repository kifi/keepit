package com.keepit.scraper.extractor

import java.net.URLEncoder

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.{ Host, URI }
import com.keepit.model.HttpProxy
import com.keepit.rover.article.{ YoutubeTrackInfo, YoutubeTrack }
import com.keepit.scraper.ScraperConfig
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.search.Lang
import com.keepit.shoebox.ShoeboxScraperClient
import org.apache.commons.lang3.StringEscapeUtils
import org.jsoup.nodes.{ Document, Element }

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import com.keepit.common.strings._

@Singleton
class YoutubeExtractorProvider @Inject() (httpFetcher: HttpFetcher, shoeboxScraperClient: ShoeboxScraperClient) extends ExtractorProvider {
  def isDefinedAt(uri: URI) = {
    uri match {
      case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) =>
        path.endsWith("/watch") && query.containsParam("v")
      case _ => false
    }
  }
  def apply(uri: URI) = new YoutubeExtractor(uri, ScraperConfig.maxContentChars, httpFetcher, shoeboxScraperClient)
}

class YoutubeExtractor(url: URI, maxContentChars: Int, httpFetcher: HttpFetcher, shoeboxScraperClient: ShoeboxScraperClient) extends JsoupBasedExtractor(url, maxContentChars) {

  def parse(doc: Document): String = {
    val headline = Option(doc.getElementById("watch-headline-title")).map(_.text).getOrElse("")
    val description = doc.select("#watch-description-text, #watch-description-extras div.content, #watch-description-extra-info").text
    val channel = doc.select("#watch7-user-header .yt-user-name").text()
    val closedCaptions = getClosedCaptions(doc).getOrElse("")
    log.info(s"[parse] headline=${headline.take(50)} desc=${description.take(50)} channel=$channel cc=${closedCaptions.take(50)}")
    Seq(headline, description, channel, closedCaptions).filter(_.nonEmpty).mkString("\n")
  }

  private def getClosedCaptions(doc: Document): Option[String] = {
    for {
      ttsUrl <- findTTSUrl(doc)
      ttsUri <- URI.safelyParse(ttsUrl)
      ttsParameters <- ttsUri.query.map(q => Seq("key", "expire", "sparams", "signature", "caps", "v", "asr_langs").flatMap(q.getParam).mkString("&"))
      track <- findTrack(ttsParameters)
    } yield getTrack(track, ttsParameters).content
  }

  private def findTTSUrl(doc: Document): Option[String] = {
    val ttsUrlPattern = """(?:'TTS_URL'|"ttsurl): "(http.*)",""".r
    val script = doc.getElementsByTag("script").toString
    ttsUrlPattern.findFirstIn(script).map { case ttsUrlPattern(url) => url.replaceAllLiterally("\\/" -> "/", "\\u0026" -> "&") }
  }

  private def findTrack(ttsParameters: String): Option[YoutubeTrackInfo] = {
    val trackListUrl = URI.parse("https://www.youtube.com/api/timedtext?asrs=1&type=list&tlangs=1&" + ttsParameters).get
    val trackListExtractor = new YoutubeTrackListExtractor(trackListUrl)
    httpFetcher.fetch(trackListUrl, proxy = getProxy(url))(trackListExtractor.process)
    val trackList = trackListExtractor.getTrackList()
    trackList.find(_.isDefault) orElse {
      trackList.find(_.isAutomatic).map(asr =>
        trackList.find(t => t.langCode == asr.langCode && !t.isAutomatic).getOrElse(asr)
      )
    } orElse trackList.find(_.langCode.lang == "en")
  }

  private def getTrack(trackInfo: YoutubeTrackInfo, ttsParameters: String): YoutubeTrack = {
    def parameter(name: String, value: String) = s"$name=${URLEncoder.encode(value, "UTF-8")}"
    val trackUrl = Seq(
      "https://www.youtube.com/api/timedtext?type=track&",
      ttsParameters,
      parameter("&name", trackInfo.name),
      parameter("&lang", trackInfo.langCode.lang),
      trackInfo.kind.map(parameter("&kind", _)).getOrElse("")
    ).mkString
    val trackUri = URI.parse(trackUrl).get
    val trackExtractor = new JsoupBasedExtractor(trackUri, maxContentChars) {
      def parse(doc: Document): String = StringEscapeUtils.unescapeXml(doc.getElementsByTag("text").map(_.text).mkString(" "))
    }
    httpFetcher.fetch(URI.parse(trackUrl).get, proxy = getProxy(url))(trackExtractor.process)
    log.info(s"[getTrack] fetched ${(if (trackInfo.isDefault) "default " else "") + (if (trackInfo.isAutomatic) "automatic " else "") + trackInfo.langTranslated} closed captions for ${url}")
    YoutubeTrack(trackInfo, trackExtractor.getContent())
  }

  private def getProxy(url: URI) = syncGetProxyP(url)

  private[extractor] def getProxyP(url: URI): Future[Option[HttpProxy]] = shoeboxScraperClient.getProxyP(url.toString())
  private[extractor] def syncGetProxyP(url: URI): Option[HttpProxy] = Await.result(getProxyP(url), 10 seconds)
}

class YoutubeTrackListExtractor(trackListUrl: URI) extends JsoupBasedExtractor(trackListUrl, Int.MaxValue) {
  def parse(doc: Document): String = doc.getElementsByTag("track").toString
  def getTrackList(): Seq[YoutubeTrackInfo] = doc.getElementsByTag("track").map(parseTrackElement)

  private def parseTrackElement(track: Element): YoutubeTrackInfo = YoutubeTrackInfo(
    id = track.attr("id").toInt,
    name = track.attr("name"),
    langCode = Lang(track.attr("lang_code")),
    langOriginal = track.attr("lang_original"),
    langTranslated = track.attr("lang_translated"),
    isDefault = track.hasAttr("lang_default"),
    kind = Option(track.attr("kind"))
  )
}
