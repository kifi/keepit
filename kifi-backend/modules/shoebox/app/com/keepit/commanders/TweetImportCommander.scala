package com.keepit.commanders

import com.keepit.common.social.twitter.RawTweet
import com.keepit.common.util.UrlClassifier
import com.keepit.controllers.website.Bookmark
import com.keepit.model._

import play.api.libs.json._

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import scala.util.{ Try, Failure, Success }
import java.io._
import com.keepit.common.logging.Logging
import org.apache.commons.io.{ Charsets, IOUtils }
import java.util.zip.{ ZipFile, ZipEntry }
import scala.collection.JavaConversions._

@ImplementedBy(classOf[TweetImportCommanderImpl])
trait TweetImportCommander {
  def isLikelyTwitterImport(file: File): Boolean
  def parseTwitterArchive(archive: File): Try[(Option[KeepSource], List[Bookmark])]
  def parseTwitterJson(jsons: Seq[JsObject]): (Option[KeepSource], List[Bookmark])
}

@Singleton
class TweetImportCommanderImpl @Inject() (urlClassifier: UrlClassifier) extends TweetImportCommander with Logging {

  def isLikelyTwitterImport(file: File): Boolean = {
    Try {
      val zip = new ZipFile(file)
      val isZipped = zip.entries().toStream.exists { ze =>
        ze.getName.startsWith("data/js/tweets")
      }
      zip.close()
      isZipped
    }.getOrElse(false)
  }

  // Parses Twitter archives, from https://twitter.com/settings/account (click “Request your archive”)
  def parseTwitterArchive(archive: File): Try[(Option[KeepSource], List[Bookmark])] = Try {
    val zip = new ZipFile(archive)
    val filesInArchive = zip.entries().toList

    val links = filesInArchive.filter(_.getName.startsWith("data/js/tweets/")).map { ze =>
      twitterEntryToJson(zip, ze).collect {
        case tweet if tweet.entities.urls.nonEmpty => rawTweetToBookmarks(tweet)
      }.flatten
    }.flatten

    (Option(KeepSource.twitterFileImport), links)
  }

  def parseTwitterJson(jsons: Seq[JsObject]): (Option[KeepSource], List[Bookmark]) = {
    val links = twitterJsonToRawTweets(jsons).collect {
      case tweet if tweet.entities.urls.nonEmpty => rawTweetToBookmarks(tweet)
    }.flatten
    (Option(KeepSource.twitterSync), links.toList)
  }

  private def rawTweetToBookmarks(tweet: RawTweet): Seq[Bookmark] = {
    val tags = tweet.entities.hashtags.map(_.text).toList
    tweet.entities.urls.filterNot(url => urlClassifier.socialActivityUrl(url.expandedUrl)).map { url =>
      Bookmark(title = None, href = url.expandedUrl, tags = tags, createdDate = Some(tweet.createdAt), originalJson = Some(tweet.originalJson))
    }
  }

  private def twitterJsonToRawTweets(jsons: Seq[JsValue]): Seq[RawTweet] = {
    jsons.map { rawTweetJson =>
      Json.fromJson[RawTweet](rawTweetJson) match {
        case JsError(fail) =>
          log.warn(s"Couldn't parse a raw tweet: $fail\n$rawTweetJson")
          None
        case JsSuccess(rt, _) => Some(rt)
      }
    }.flatten
  }

  private def twitterEntryToJson(zip: ZipFile, entry: ZipEntry): Seq[RawTweet] = {
    val is = zip.getInputStream(entry)
    val str = IOUtils.toString(is, Charsets.UTF_8)
    is.close()
    Try(Json.parse(str.substring(str.indexOf("=") + 1)).as[JsArray]) match {
      case Success(rawTweetsJson) => twitterJsonToRawTweets(rawTweetsJson.value)
      case Failure(ex) => Seq()
    }
  }
}
