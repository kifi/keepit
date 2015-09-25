package com.keepit.controllers.website

import java.util.concurrent.TimeoutException
import java.util.zip.{ ZipEntry, ZipInputStream, ZipFile }
import com.keepit.common.social.twitter.RawTweet
import com.keepit.common.time
import org.joda.time.format.{ DateTimeFormat, DateTimeParser, ISODateTimeFormat, DateTimeFormatterBuilder }

import scala.collection.JavaConversions._

import com.keepit.commanders.{ KeepCommander, KeepInterner }
import com.keepit.common.akka.{ TimeoutFuture, SafeFuture }
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick._
import com.keepit.common.util.UrlClassifier
import com.keepit.model._
import org.apache.commons.io.{ Charsets, IOUtils, FileUtils }
import play.api.libs.Files.TemporaryFile

import play.api.libs.json._

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import play.api.mvc.{ MaxSizeExceeded, Request }
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source
import scala.util.{ Try, Failure, Success }
import org.jsoup.Jsoup
import java.io._
import com.keepit.common.db.Id
import java.util.{ Locale, UUID }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.strings.humanFriendlyToken
import com.keepit.common.performance._

import org.joda.time.DateTime
import scala.concurrent.duration.Duration

class BookmarkImporter @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    urlClassifier: UrlClassifier,
    keepInterner: KeepInterner,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory,
    keepsCommander: KeepCommander,
    twitterArchiveParser: TwitterArchiveParser,
    netscapeBookmarkParser: NetscapeBookmarkParser,
    evernoteParser: EvernoteParser,
    clock: Clock,
    implicit val executionContext: ExecutionContext,
    implicit val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with Logging {

  def importFileToLibrary(pubId: PublicId[Library]) = UserAction.async(parse.maxLength(1024 * 1024 * 12, parse.temporaryFile)) { request =>
    val startMillis = clock.getMillis()
    val id = humanFriendlyToken(8)
    log.info(s"[bmFileImport:$id] Processing bookmark file import for ${request.userId}")

    request.body match {
      case Right(bookmarks) =>

        val uploadAttempt = Try(bookmarks).flatMap { file =>
          if (twitterArchiveParser.canProbablyParse(file)) {
            log.info(s"[bmFileImport:$id] Twitter import, ${file.file.getAbsoluteFile}")
            twitterArchiveParser.parse(file)
          } else if (evernoteParser.canProbablyParse(file)) {
            evernoteParser.parse(file)
          } else {
            log.info(s"[bmFileImport:$id] Netscape import, ${file.file.getAbsoluteFile}")
            netscapeBookmarkParser.parse(file)
          }
        }.map {
          case ((sourceOpt, parsed)) =>
            implicit val timeout = Duration("10 seconds")
            TimeoutFuture {
              processBookmarkExtraction(sourceOpt, parsed, pubId, LoggingFields(startMillis, id, request))
            }.recover { case ex: TimeoutException => None } /* Since it didn't fail yet, we'll probably succeed. Don't keep the user waiting. */
        } match {
          case Success(fut) => fut
          case Failure(ex) => Future.failed(ex)
        }

        uploadAttempt.map {
          case Some((keepSize, tagSize)) =>
            log.info(s"[bmFileImport:$id] Returning to user in ${clock.getMillis() - startMillis}ms. Success for ${request.userId}: $keepSize keeps processed, $tagSize tags.")
            Ok(Json.obj("count" -> keepSize))
          case None =>
            log.info(s"[bmFileImport:$id] Returning to user in ${clock.getMillis() - startMillis}ms, but timed out before finished processing.")
            Ok(Json.obj("in_progress" -> true))
        }.recover {
          case ex =>
            log.info(s"[bmFileImport:$id] Failure (ex) in ${clock.getMillis() - startMillis}ms")
            log.error(s"Could not import bookmark file for ${request.userId}, had an exception.", ex)
            BadRequest(Json.obj("error" -> "couldnt_complete", "message" -> ex.getMessage))
        }
      case Left(err) =>
        log.info(s"[bmFileImport:$id] Failure (too large) in ${clock.getMillis() - startMillis}ms")
        log.warn(s"Could not import bookmark file for ${request.userId}, size too big: ${err.length}.\n${err.toString}")
        Future.successful(BadRequest(Json.obj("error" -> "file_too_large", "size" -> err.length)))
    }
  }

  // remove this, should not be in here
  def processDirectTwitterData(userId: Id[User], libraryId: Id[Library], tweets: Seq[JsObject]): Unit = {
    implicit val context = heimdalContextBuilderFactoryBean().build
    val (sourceOpt, parsed) = twitterArchiveParser.parseTwitterJson(tweets)
    log.info(s"[TweetSync] Got ${parsed.length} Bookmarks out of ${tweets.length} tweets")
    val tagSet = parsed.flatMap { bm => bm.tags }.toSet

    val tags = tagSet.map { tagStr =>
      tagStr.trim -> keepsCommander.getOrCreateTag(userId, tagStr.trim)(context)
    }.toMap
    val taggedKeeps = parsed.map {
      case Bookmark(t, h, tagNames, createdDate, originalJson) =>
        val keepTagNames = tagNames.flatMap(tags.get).map(_.name.tag)
        Bookmark(t, h, keepTagNames, createdDate, originalJson)
    }

    val (importId, rawKeeps) = createRawKeeps(userId, sourceOpt, taggedKeeps, libraryId)

    keepInterner.persistRawKeeps(rawKeeps, Some(importId))
  }

  case class LoggingFields(startMillis: Long, id: String, request: UserRequest[Either[MaxSizeExceeded, play.api.libs.Files.TemporaryFile]])

  private def processBookmarkExtraction(sourceOpt: Option[KeepSource], parsed: Seq[Bookmark], pubId: PublicId[Library], lf: LoggingFields): Future[Option[(Int, Int)]] = {
    implicit val context = heimdalContextBuilderFactoryBean.withRequestInfoAndSource(lf.request, sourceOpt.getOrElse(KeepSource.bookmarkFileImport)).build
    SafeFuture("processBookmarkExtraction") {
      log.info(s"[bmFileImport:${lf.id}] Parsed in ${clock.getMillis() - lf.startMillis}ms")
      val tagMap = scala.collection.mutable.Map.empty[String, String]
      parsed.foreach { bm =>
        bm.tags.map { tagName =>
          tagMap += (tagName.toLowerCase -> tagName)
        }
      }

      val importTag = keepsCommander.getOrCreateTag(lf.request.userId, "imported-links")(context)

      val tags = tagMap.values.toSeq.map { tagStr =>
        tagStr.trim -> timing(s"uploadBookmarkFile(${lf.request.userId}) -- getOrCreateTag(${tagStr.trim})", 50) { keepsCommander.getOrCreateTag(lf.request.userId, tagStr.trim)(context) }
      }.toMap
      val taggedKeeps = parsed.map {
        case Bookmark(t, h, tagNames, createdDate, originalJson) =>
          val keepTags = tagNames.flatMap(tags.get) :+ importTag
          Bookmark(t, h, keepTags.map(_.name.tag), createdDate, originalJson)
      }
      log.info(s"[bmFileImport:${lf.id}] Tags extracted in ${clock.getMillis() - lf.startMillis}ms")

      val (importId, rawKeeps) = Library.decodePublicId(pubId) match {
        case Failure(ex) =>
          // airbrake.notify(s"importing to library with invalid pubId ${pubId}")
          throw new Exception(s"importing to library with invalid pubId ${pubId}")
          ("", List.empty[RawKeep])
        case Success(id) =>
          createRawKeeps(lf.request.userId, sourceOpt, taggedKeeps, id)
      }

      log.info(s"[bmFileImport:${lf.id}] Raw keep start persisting in ${clock.getMillis() - lf.startMillis}ms")
      keepInterner.persistRawKeeps(rawKeeps, Some(importId))

      log.info(s"[bmFileImport:${lf.id}] Raw keep finished persisting in ${clock.getMillis() - lf.startMillis}ms")

      log.info(s"[bmFileImport:${lf.id}] Done in ${clock.getMillis() - lf.startMillis}ms. Successfully processed bookmark file import for ${lf.request.userId}. ${rawKeeps.length} keeps processed, ${tags.size} tags.")

      Some((rawKeeps.length, tags.size))
    }
  }

  private def createRawKeeps(userId: Id[User], source: Option[KeepSource], bookmarks: Seq[Bookmark], libraryId: Id[Library]) = {
    val importId = UUID.randomUUID.toString
    val rawKeeps = bookmarks.map {
      case Bookmark(title, href, hashtags, createdDate, originalJson) =>
        val titleOpt = if (title.nonEmpty && title.exists(_.nonEmpty) && title.exists(_ != href)) Some(title.get) else None
        val hashtagsArray = if (hashtags.nonEmpty) {
          Some(JsArray(hashtags.map(Json.toJson(_))))
        } else {
          None
        }

        RawKeep(userId = userId,
          title = titleOpt,
          url = href,
          importId = Some(importId),
          source = source.getOrElse(KeepSource.bookmarkFileImport),
          originalJson = originalJson,
          installationId = None,
          keepTags = hashtagsArray,
          libraryId = Some(libraryId),
          createdDate = createdDate)
    }
    (importId, rawKeeps)
  }
}

case class Bookmark(title: Option[String], href: String, tags: List[String], createdDate: Option[DateTime], originalJson: Option[JsValue])

trait ImportParser {
  def canProbablyParse(file: TemporaryFile): Boolean
  def parse(file: TemporaryFile): Try[(Option[KeepSource], List[Bookmark])]
}

@Singleton
class NetscapeBookmarkParser @Inject() () extends ImportParser with ZipParser {
  def canProbablyParse(file: TemporaryFile): Boolean = {
    // Jsoup is *very* lenient about what it accepts
    file.file.length < 1024 * 1024 * 10
  }

  def parse(bookmarks: TemporaryFile): Try[(Option[KeepSource], List[Bookmark])] = Try {
    // We support bookmark exports that were zipped. Determining if that's the case here.
    val zipFile = if (containsFileName(bookmarks.file, htmlFile)) {
      parseZip(bookmarks)
    } else {
      None
    }
    parseHtml(zipFile.getOrElse(bookmarks))
  }.flatten

  def parseHtml(bookmarks: TemporaryFile): Try[(Option[KeepSource], List[Bookmark])] = Try {
    // This is a standard for bookmark exports.
    // http://msdn.microsoft.com/en-us/library/aa753582(v=vs.85).aspx

    import scala.collection.JavaConversions._

    val parsed = Jsoup.parse(bookmarks.file, "UTF-8")
    val title = Option(parsed.select("title").text())

    val source = title match {
      case Some("Kippt Bookmarks") => Some(KeepSource("Kippt"))
      case Some("Pocket Export") => Some(KeepSource("Pocket"))
      case Some("Instapaper: Export") => Some(KeepSource("Instapaper"))
      case Some(title) if title.contains("Diigo") => Some(KeepSource("Diigo"))
      case _ => None
    }

    val links = parsed.select("dt a, li a")

    val extracted = links.iterator().map { elem =>
      for {
        title <- Option(elem.text())
        href <- Option(elem.attr("href"))
      } yield {
        val lists = Option(elem.attr("list")).getOrElse("")
        val tags = Option(elem.attr("tags")).getOrElse("")

        val tagList = (lists + tags).split(",").map(_.trim).filter(_.nonEmpty).toList.distinct
        val createdDate = Option(elem.attr("add_date"))
          .orElse(Option(elem.attr("last_visit")))
          .orElse(Option(elem.attr("last_modified")))
          .flatMap(bookmarkDateStrToDateTime)

        Bookmark(Some(title), href, tagList, createdDate, Some(Json.obj("href" -> elem.html())))
      }
    }.toList.flatten
    (source, extracted)
  }

  val bookmarkDateStrToDateTime: String => Option[DateTime] = {
    case x if x.nonEmpty =>
      val parsedAsNumericOpt = Try {
        x.toLong match { // This breaks in 2020.
          case l if l > 1000000000L && l < 1600000000L => // Unix time
            Some(new DateTime(l * 1000))
          case l if l > 1000000000000L && l < 1600000000000L => // ms since epoch
            Some(new DateTime(l))
          case l if l > 1000000000000000L && l < 1600000000000000L => // μs since epoch (Google Bookmarks uses this)
            Some(new DateTime(l / 1000))
          case _ =>
            None
        }
      }.toOption.flatten

      parsedAsNumericOpt.orElse(Try(new DateTime(x)).toOption)
    case otherwise => None
  }

  private def parseZip(bookmarks: TemporaryFile) = {
    val zipFileOpt = Try(new ZipFile(bookmarks.file)).toOption
    zipFileOpt.flatMap { zipFile =>
      val res = getFiles(zipFile, htmlFile, { ze =>
        val is = zipFile.getInputStream(ze)
        val extractedF = TemporaryFile("bm-", ".zip")
        FileUtils.copyInputStreamToFile(is, extractedF.file)
        is.close()
        extractedF
      }).headOption
      zipFile.close()
      res
    }
  }

  private val htmlFile = (f: String) => f.endsWith("html") || f.endsWith("htm")
}

@Singleton
class EvernoteParser @Inject() () extends ImportParser {
  def canProbablyParse(file: TemporaryFile): Boolean = {
    file.file.length < 1024 * 1024 * 20 &&
      Try(Source.fromFile(file.file, 255).getLines().take(2).toList.mkString("").contains("xml.evernote.com")).getOrElse(false)
  }

  def parse(bookmarks: TemporaryFile): Try[(Option[KeepSource], List[Bookmark])] = Try {
    val notes = Jsoup.parse(bookmarks.file, "UTF-8").select("note").iterator().toStream

    val parsed = notes.flatMap { note =>
      val createdAt = Option(note.select("updated")).orElse(Option(note.select("created"))).flatMap(s => Option(s.text())).map(formatter.parseDateTime)
      val links = Jsoup.parse(note.select("content").text()).select("en-note a").iterator().toStream.flatMap { elem =>
        val href = Option(elem.attr("href"))
        val text = Option(elem.text()).filterNot(href.contains)

        href.collect { case h if h.length > 11 =>
          Bookmark(text, h, List.empty, createdAt, Some(Json.obj("href" -> elem.html())))
        }
      }
      links
    }

    (Option(KeepSource.evernote), parsed.toList)
  }

  private val formatter = new DateTimeFormatterBuilder().append(
    ISODateTimeFormat.dateTime.getPrinter,
    Array[DateTimeParser](ISODateTimeFormat.dateTime.getParser, DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ").getParser))
    .toFormatter.withLocale(Locale.ENGLISH).withZone(time.DEFAULT_DATE_TIME_ZONE)
}

trait ZipParser extends Logging {

  def containsFileName(file: File, nameMatcher: String => Boolean): Boolean = {
    val zipT = Try(new ZipFile(file))
    zipT.map { zip =>
      val matches = safelyTraverseZip(zip).exists(ze => nameMatcher(ze.getName))
      zip.close()
      matches
    }.getOrElse(false)
  }

  def getFiles[T](zip: ZipFile, nameMatcher: String => Boolean, fileParser: ZipEntry => T): Stream[T] = {
    safelyTraverseZip(zip).map { ze =>
      Try(fileParser(ze))
    }.collect { case Success(ze) => ze }
  }

  private def safelyTraverseZip(zip: ZipFile): Stream[ZipEntry] = {
    Try {
      require(zip.size() < 1024 * 1024 * 10, "ZIP archive too large")
      zip.entries().toStream.take(1000).flatMap { ze =>
        if (!ze.getName.endsWith("zip") && ze.getSize < (1024 * 1024 * 5)) {
          Some(ze)
        } else {
          None
        }
      }
    }.getOrElse(Stream.empty[ZipEntry])
  }

}
@ImplementedBy(classOf[TwitterArchiveParserImpl])
trait TwitterArchiveParser extends ImportParser {
  def parseTwitterJson(jsons: Seq[JsObject]): (Option[KeepSource], List[Bookmark])
}

@Singleton
class TwitterArchiveParserImpl @Inject() (urlClassifier: UrlClassifier) extends TwitterArchiveParser with ZipParser {
  def canProbablyParse(file: TemporaryFile): Boolean = {
    containsFileName(file.file, _.startsWith("data/js/tweets"))
  }

  // Parses Twitter archives, from https://twitter.com/settings/account (click “Request your archive”)
  def parse(file: TemporaryFile): Try[(Option[KeepSource], List[Bookmark])] = Try {
    val zipT = Try(new ZipFile(file.file))

    val links = zipT.map { zip =>
      val res = getFiles[Seq[Bookmark]](zip, _.startsWith("data/js/tweets/"), { ze =>
        twitterEntryToJson(zip, ze).collect {
          case tweet if tweet.entities.urls.nonEmpty => rawTweetToBookmarks(tweet)
        }.flatten
      }).toList.flatten
      zip.close()
      res
    }.getOrElse(List.empty)

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
    jsons.flatMap { rawTweetJson =>
      Json.fromJson[RawTweet](rawTweetJson) match {
        case JsError(fail) =>
          log.warn(s"Couldn't parse a raw tweet: $fail\n$rawTweetJson")
          None
        case JsSuccess(rt, _) => Some(rt)
      }
    }
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