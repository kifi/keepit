package com.keepit.controllers.website

import java.net.URLConnection
import java.util.concurrent.TimeoutException

import com.keepit.classify.{ Domain, DomainRepo, DomainStates }
import com.keepit.commanders.{ KeepsCommander, KeepInterner, UserCommander }
import com.keepit.common.akka.{ TimeoutFuture, SafeFuture }
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.URI
import com.keepit.common.social.twitter.RawTweet
import com.keepit.model._

import play.api.libs.json._
import play.api.libs.functional.syntax._

import com.google.inject.Inject
import play.api.mvc.{ MaxSizeExceeded, Request }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Failure, Success }
import org.jsoup.Jsoup
import java.io._
import com.keepit.common.db.Id
import java.util.UUID
import com.keepit.heimdal.{ HeimdalContextBuilderFactory, HeimdalContextBuilder }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.strings.humanFriendlyToken
import org.apache.commons.io.{ Charsets, IOUtils, FileUtils }
import com.keepit.common.performance._

import org.joda.time.DateTime
import java.util.zip.{ ZipInputStream, ZipFile, ZipEntry }
import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

class BookmarkImporter @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    rawKeepFactory: RawKeepFactory,
    keepInterner: KeepInterner,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory,
    keepsCommander: KeepsCommander,
    clock: Clock,
    implicit val executionContext: ExecutionContext,
    implicit val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with Logging {

  def importFileToLibrary(pubId: PublicId[Library]) = UserAction.async(parse.maxLength(1024 * 1024 * 12, parse.temporaryFile)) { request =>
    val startMillis = clock.getMillis()
    val id = humanFriendlyToken(8)
    log.info(s"[bmFileImport:$id] Processing bookmark file import for ${request.userId}")

    request.body match {
      case Right(bookmarks) =>

        val uploadAttempt = Try(bookmarks.file).flatMap({ file =>
          val isTwitterZip = isLikelyTwitterImport(file)
          if (isTwitterZip) {
            log.info(s"[bmFileImport:$id] Twitter import, ${file.getAbsoluteFile}")
            parseTwitterArchive(file)
          } else {
            log.info(s"[bmFileImport:$id] Netscape import, ${file.getAbsoluteFile}")
            parseNetscapeBookmarks(file)
          }
        }).map {
          case (sourceOpt, parsed) =>
            implicit val timeout = Duration("10 seconds")
            TimeoutFuture {
              processBookmarkExtraction(sourceOpt, parsed, pubId, LoggingFields(startMillis, id, request))
            }.recover {
              case ex: TimeoutException => // Since it didn't fail yet, we'll probably succeed. Don't keep the user waiting.
                None
            }
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

  def processDirectTwitterData(userId: Id[User], libraryId: Id[Library], tweets: Seq[JsObject]): Unit = {
    implicit val context = heimdalContextBuilderFactoryBean().build
    val (sourceOpt, parsed) = parseTwitterJson(tweets)
    val tagSet = scala.collection.mutable.Set.empty[String]
    parsed.foreach { bm =>
      bm.tags.map { tagName =>
        tagSet.add(tagName)
      }
    }

    val tags = tagSet.map { tagStr =>
      tagStr.trim -> keepsCommander.getOrCreateTag(userId, tagStr.trim)(context)
    }.toMap
    val taggedKeeps = parsed.map {
      case Bookmark(t, h, tagNames, createdDate, originalJson) =>
        val keepTags = tagNames.map(tags.get).flatten.map(_.id.get)
        BookmarkWithTagIds(t, h, keepTags, createdDate, originalJson)
    }

    val (importId, rawKeeps) = createRawKeeps(userId, sourceOpt, taggedKeeps, libraryId)

    keepInterner.persistRawKeeps(rawKeeps, Some(importId))
  }

  // Internal

  private def isLikelyTwitterImport(file: File) = {
    Try {
      val zip = new ZipFile(file)
      val isZipped = zip.entries().toStream.exists { ze =>
        ze.getName.startsWith("data/js/tweets")
      }
      zip.close()
      isZipped
    }.getOrElse(false)
  }

  case class LoggingFields(startMillis: Long, id: String, request: UserRequest[Either[MaxSizeExceeded, play.api.libs.Files.TemporaryFile]])

  private def processBookmarkExtraction(sourceOpt: Option[KeepSource], parsed: Seq[Bookmark], pubId: PublicId[Library], lf: LoggingFields): Future[Option[(Int, Int)]] = {
    implicit val context = heimdalContextBuilderFactoryBean.withRequestInfoAndSource(lf.request, sourceOpt.getOrElse(KeepSource.bookmarkFileImport)).build
    SafeFuture("processBookmarkExtraction") {
      log.info(s"[bmFileImport:${lf.id}] Parsed in ${clock.getMillis() - lf.startMillis}ms")
      val tagSet = scala.collection.mutable.Set.empty[String]
      parsed.foreach { bm =>
        bm.tags.map { tagName =>
          tagSet.add(tagName)
        }
      }

      val importTag = keepsCommander.getOrCreateTag(lf.request.userId, "Imported links")(context)

      val tags = tagSet.map { tagStr =>
        tagStr.trim -> timing(s"uploadBookmarkFile(${lf.request.userId}) -- getOrCreateTag(${tagStr.trim})", 50) { keepsCommander.getOrCreateTag(lf.request.userId, tagStr.trim)(context) }
      }.toMap
      val taggedKeeps = parsed.map {
        case Bookmark(t, h, tagNames, createdDate, originalJson) =>
          val keepTags = tagNames.map(tags.get).flatten.map(_.id.get) :+ importTag.id.get
          BookmarkWithTagIds(t, h, keepTags, createdDate, originalJson)
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

      log.info(s"[bmFileImport:${lf.id}] Done in ${clock.getMillis() - lf.startMillis}ms. Successfully processed bookmark file import for ${lf.request.userId}. $rawKeeps.length keeps processed, $tags.size tags.")

      Some((rawKeeps.length, tags.size))
    }
  }

  /* Parses Netscape-bookmark formatted file, extracting useful fields */
  private def parseNetscapeBookmarks(bookmarks: File): Try[(Option[KeepSource], List[Bookmark])] = Try {
    // This is a standard for bookmark exports.
    // http://msdn.microsoft.com/en-us/library/aa753582(v=vs.85).aspx

    import scala.collection.JavaConversions._

    val parsed = Jsoup.parse(bookmarks, "UTF-8")
    val title = Option(parsed.select("title").text())

    val source = title match {
      case Some("Kippt Bookmarks") => Some(KeepSource("Kippt"))
      case Some("Pocket Export") => Some(KeepSource("Pocket"))
      case Some("Instapaper: Export") => Some(KeepSource("Instapaper"))
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

        val tagList = (lists + tags).split(",").map(_.trim).filter(_.nonEmpty).toList
        val createdDate: Option[DateTime] = Option(elem.attr("add_date")).map { x =>
          Try {
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
        }.flatten

        // This may be useful in the future, but we currently are not using them:
        // val lastVisitDate = Option(elem.attr("last_visit"))

        Bookmark(Some(title), href, tagList, createdDate, Some(Json.obj("href" -> elem.html())))
      }
    }.toList.flatten
    (source, extracted)
  }

  private def rawTweetToBookmarks(tweet: RawTweet): Seq[Bookmark] = {
    val tags = tweet.entities.hashtags.map(_.text).toList
    tweet.entities.urls.map { url =>
      Bookmark(title = None, href = url.expandedUrl, tags = tags, createdDate = Some(tweet.createdAt), originalJson = Some(tweet.originalJson))
    }
  }

  private def parseTwitterJson(jsons: Seq[JsObject]): (Option[KeepSource], List[Bookmark]) = {
    val links = twitterJsonToRawTweets(jsons).collect {
      case tweet if tweet.entities.urls.nonEmpty => rawTweetToBookmarks(tweet)
    }.flatten
    (Option(KeepSource.twitterFileImport), links.toList)
  }

  // Parses Twitter archives, from https://twitter.com/settings/account (click “Request your archive”)
  private def parseTwitterArchive(archive: File): Try[(Option[KeepSource], List[Bookmark])] = Try {
    val zip = new ZipFile(archive)
    val filesInArchive = zip.entries().toList

    val links = filesInArchive.filter(_.getName.startsWith("data/js/tweets/")).map { ze =>
      twitterEntryToJson(zip, ze).collect {
        case tweet if tweet.entities.urls.nonEmpty => rawTweetToBookmarks(tweet)
      }.flatten
    }.flatten

    (Option(KeepSource.twitterFileImport), links)
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

  private def createRawKeeps(userId: Id[User], source: Option[KeepSource], bookmarks: Seq[BookmarkWithTagIds], libraryId: Id[Library]) = {
    val importId = UUID.randomUUID.toString
    val rawKeeps = bookmarks.map {
      case BookmarkWithTagIds(title, href, tagIds, createdDate, originalJson) =>
        val titleOpt = if (title.nonEmpty && title.exists(_.nonEmpty)) Some(title.get) else None
        val tags = tagIds.map(_.id.toString).mkString(",") match {
          case s if s.isEmpty => None
          case s => Some(s)
        }

        RawKeep(userId = userId,
          title = titleOpt,
          url = href,
          isPrivate = true,
          importId = Some(importId),
          source = source.getOrElse(KeepSource.bookmarkFileImport),
          originalJson = originalJson,
          installationId = None,
          tagIds = tags,
          libraryId = Some(libraryId),
          createdDate = createdDate)
    }
    (importId, rawKeeps)
  }

}

case class Bookmark(title: Option[String], href: String, tags: List[String], createdDate: Option[DateTime], originalJson: Option[JsValue])
case class BookmarkWithTagIds(title: Option[String], href: String, tags: List[Id[Collection]], createdAt: Option[DateTime], originalJson: Option[JsValue])
