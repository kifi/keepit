package com.keepit.rover.article.fetcher

import java.net.URLEncoder

import com.google.inject.Inject
import com.keepit.common.core._
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.common.strings._
import com.keepit.common.time.{ Clock, _ }
import com.keepit.rover.article.YoutubeArticle
import com.keepit.rover.article.content.{ YoutubeContent, YoutubeTrack, YoutubeTrackInfo, YoutubeVideo }
import com.keepit.rover.document.{ JsoupDocument, RoverDocumentFetcher }
import com.keepit.rover.fetcher.FetchResult
import com.keepit.rover.store.RoverArticleStore
import com.keepit.search.Lang
import org.apache.commons.lang3.StringEscapeUtils
import org.joda.time.DateTime
import org.jsoup.nodes.Element

import scala.collection.JavaConversions._
import scala.concurrent.{ ExecutionContext, Future }

object YoutubeArticleFetcher {
  val ttsUrlPattern = """(?:'TTS_URL'|"ttsurl): "(http.*)",""".r
  val ttsParamKeys = Seq("key", "expire", "sparams", "signature", "caps", "v", "asr_langs")
  def trackListUrl(ttsParameters: Seq[Param]): String = "https://www.youtube.com/api/timedtext?asrs=1&type=list&tlangs=1&" + ttsParameters.mkString("&")
  def trackUrl(trackInfo: YoutubeTrackInfo, ttsParameters: Seq[Param]): String = {
    Seq(
      "https://www.youtube.com/api/timedtext?type=track&",
      ttsParameters,
      parameter("&name", trackInfo.name),
      parameter("&lang", trackInfo.langCode.lang),
      trackInfo.kind.map(parameter("&kind", _)).getOrElse("")
    ).mkString
  }
  private def parameter(name: String, value: String) = s"$name=${URLEncoder.encode(value, "UTF-8")}"
}
class YoutubeArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[YoutubeArticle] with Logging {
  import com.keepit.rover.article.fetcher.YoutubeArticleFetcher._

  def fetch(request: ArticleFetchRequest[YoutubeArticle])(implicit ec: ExecutionContext): Future[Option[YoutubeArticle]] = {
    ArticleFetcher.fetchAndCompare(request, articleStore)(doFetch)
  }

  private def doFetch(url: String, ifModifiedSince: Option[DateTime], shouldThrottle: Boolean)(implicit ec: ExecutionContext): Future[FetchResult[YoutubeArticle]] = {
    documentFetcher.fetchJsoupDocument(url, ifModifiedSince, shouldThrottle).flatMap { result =>
      result.flatMap { doc =>
        getVideoContent(doc).imap { videoContent =>
          val content = YoutubeContent(
            destinationUrl = result.context.request.destinationUrl,
            title = doc.getTitle,
            description = doc.getDescription,
            keywords = doc.getMetaKeywords,
            authors = doc.getAuthor.toSeq,
            openGraphType = doc.getOpenGraphType,
            publishedAt = doc.getPublishedAt,
            http = result.context,
            normalization = doc.getNormalizationInfo,
            video = videoContent
          )
          YoutubeArticle(clock.now(), url, content)
        }
      }
    }
  }

  private def getVideoContent(doc: JsoupDocument)(implicit ec: ExecutionContext): Future[YoutubeVideo] = {
    val futureTracks = getTracks(doc)
    val headline = SafeOpt(doc.doc.getElementById("watch-headline-title")).map(_.text).filter(_.nonEmpty)
    val description = SafeOpt(doc.doc.select("#watch-description-text .content").text()).getOrElse("")
    val channel = SafeOpt(doc.doc.select("#watch7-user-header .yt-user-name").text()).getOrElse("")
    val tags = SafeOpt(doc.doc.select("#watch-description-extras .content .watch-info-tag-list").map(_.text()).filter(_.nonEmpty)).getOrElse(Seq.empty)
    val viewCount = SafeOpt(doc.doc.select("#watch7-views-info .watch-view-count").text().replaceAll("\\D", "").toInt).getOrElse(0)

    // todo: Add alerting so we know if youtube breaks these selectors

    futureTracks.imap { tracks =>
      YoutubeVideo(
        headline,
        description,
        tags,
        channel,
        tracks,
        viewCount
      ) tap { video => log.info(s"Fetched Youtube video: $video") }
    }
  }

  private def getTracks(doc: JsoupDocument)(implicit ec: ExecutionContext): Future[Seq[YoutubeTrack]] = {
    getTTSParameters(doc) match {
      case None => Future.successful(Seq.empty)
      case Some(ttsParameters) =>
        fetchTrackList(ttsParameters).flatMap {
          case FetchResult(missingTrackListContext, None) => {
            log.error(s"Failed to fetch Youtube track list: $missingTrackListContext")
            Future.successful(Seq.empty)
          }
          case FetchResult(_, Some(trackList)) => {
            val trackFutures = trackList.map { trackInfo =>
              fetchTrack(trackInfo, ttsParameters).imap {
                case FetchResult(context, track) =>
                  if (track.isEmpty) {
                    log.error(s"Failed to fetch Youtube track: $context")
                  }
                  track
              }
            }
            Future.sequence(trackFutures).imap(_.flatten)
          }
        }
    }
  }

  private def getTTSParameters(doc: JsoupDocument): Option[Seq[Param]] = {
    for {
      ttsUrl <- findTTSUrl(doc)
      ttsUri <- URI.safelyParse(ttsUrl)
      ttsQuery <- ttsUri.query
    } yield ttsParamKeys.flatMap(ttsQuery.getParam)
  }

  private def findTTSUrl(doc: JsoupDocument): Option[String] = {
    val script = doc.doc.getElementsByTag("script").toString
    ttsUrlPattern.findFirstIn(script).map { case ttsUrlPattern(url) => url.replaceAllLiterally("\\/" -> "/", "\\u0026" -> "&") }
  }

  private def fetchTrackList(ttsParameters: Seq[Param])(implicit ec: ExecutionContext): Future[FetchResult[Seq[YoutubeTrackInfo]]] = {
    documentFetcher.fetchJsoupDocument(trackListUrl(ttsParameters), shouldThrottle = false).map { result =>
      result.map { doc =>
        doc.doc.getElementsByTag("track").map(parseTrackElement)
      }
    }
  }

  private def parseTrackElement(track: Element): YoutubeTrackInfo = YoutubeTrackInfo(
    id = track.attr("id").toInt,
    name = track.attr("name"),
    langCode = Lang(track.attr("lang_code")),
    langOriginal = track.attr("lang_original"),
    langTranslated = track.attr("lang_translated"),
    isDefault = track.hasAttr("lang_default"),
    kind = Option(track.attr("kind"))
  )

  private def fetchTrack(trackInfo: YoutubeTrackInfo, ttsParameters: Seq[Param])(implicit ec: ExecutionContext): Future[FetchResult[YoutubeTrack]] = {
    documentFetcher.fetchJsoupDocument(trackUrl(trackInfo, ttsParameters), shouldThrottle = false).map { result =>
      result.map { doc =>
        val closedCaptions = StringEscapeUtils.unescapeXml(doc.doc.getElementsByTag("text").map(_.text).mkString(" "))
        log.info(s"[fetchTrack] fetched ${(if (trackInfo.isDefault) "default " else "") + (if (trackInfo.isAutomatic) "automatic " else "") + trackInfo.langTranslated} closed captions.")
        YoutubeTrack(trackInfo, closedCaptions)
      }
    }
  }
}
