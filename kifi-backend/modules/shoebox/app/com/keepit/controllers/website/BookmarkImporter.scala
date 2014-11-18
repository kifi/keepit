package com.keepit.controllers.website

import com.keepit.classify.{ Domain, DomainRepo, DomainStates }
import com.keepit.commanders.{ KeepsCommander, KeepInterner, UserCommander }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.URI
import com.keepit.model._

import play.api.libs.json.{ JsNumber, Json }

import com.google.inject.Inject
import scala.util.{ Try, Failure, Success }
import org.jsoup.Jsoup
import java.io.{ BufferedWriter, FileWriter, FileOutputStream, File }
import com.keepit.common.db.Id
import java.util.UUID
import com.keepit.heimdal.{ HeimdalContextBuilderFactory, HeimdalContextBuilder }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.strings.humanFriendlyToken
import org.apache.commons.io.FileUtils
import com.keepit.common.performance._

import org.joda.time.DateTime

class BookmarkImporter @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    rawKeepFactory: RawKeepFactory,
    keepInterner: KeepInterner,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory,
    keepsCommander: KeepsCommander,
    clock: Clock,
    implicit val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with Logging {

  def uploadBookmarkFile(public: Boolean = false) = UserAction(parse.maxLength(1024 * 1024 * 12, parse.temporaryFile)) { request =>
    val startMillis = clock.getMillis()
    val id = humanFriendlyToken(8)
    log.info(s"[bmFileImport:$id] Processing bookmark file import for ${request.userId}")

    request.body match {
      case Right(bookmarks) =>
        implicit val context = heimdalContextBuilderFactoryBean.withRequestInfoAndSource(request, KeepSource.bookmarkFileImport).build

        Try(bookmarks.file)
          .flatMap(parseNetscapeBookmarks)
          .map {
            case (sourceOpt, parsed) =>

              log.info(s"[bmFileImport:$id] Parsed in ${clock.getMillis() - startMillis}ms")
              val tagSet = scala.collection.mutable.Set.empty[String]
              parsed.foreach {
                case (_, _, tagsOpt, _) =>
                  tagsOpt.map { tagName =>
                    tagSet.add(tagName)
                  }
              }

              val importTag = sourceOpt match {
                case Some(source) => keepsCommander.getOrCreateTag(request.userId, "Imported from " + source.value)(context)
                case None => keepsCommander.getOrCreateTag(request.userId, "Imported links")(context)
              }
              val tags = tagSet.map { tagStr =>
                tagStr.trim -> timing(s"uploadBookmarkFile(${request.userId}) -- getOrCreateTag(${tagStr.trim})", 50) { keepsCommander.getOrCreateTag(request.userId, tagStr.trim)(context) }
              }.toMap
              val taggedKeeps = parsed.map {
                case (t, h, tagNames, createdDate) =>
                  val keepTags = tagNames.map(tags.get).flatten.map(_.id.get) :+ importTag.id.get
                  (t, h, keepTags, createdDate)
              }
              log.info(s"[bmFileImport:$id] Tags extracted in ${clock.getMillis() - startMillis}ms")

              val (importId, rawKeeps) = createRawKeeps(request.userId, sourceOpt, taggedKeeps, public, None)

              log.info(s"[bmFileImport:$id] Raw keep start persisting in ${clock.getMillis() - startMillis}ms")
              keepInterner.persistRawKeeps(rawKeeps, Some(importId))

              log.info(s"[bmFileImport:$id] Raw keep finished persisting in ${clock.getMillis() - startMillis}ms")

              (rawKeeps.length, tags.size)
          } match {
            case Success((keepSize, tagSize)) =>
              log.info(s"[bmFileImport:$id] Done in ${clock.getMillis() - startMillis}ms")
              log.info(s"Successfully processed bookmark file import for ${request.userId}. $keepSize keeps processed, $tagSize tags.")
              Ok(s"""{"done": "kindly let the user know that it is working and may take a sec", "count": $keepSize}""")
            case Failure(oops) =>
              // todo(Andrew): Remove this soon, or anonymize it. This is here to isolate some issues.
              val file: File = new File(s"bad-bookmarks-${request.userId}.html")
              try {
                FileUtils.copyFile(bookmarks.file, file)
              } catch {
                case ex: Throwable =>
                  log.error("[bmFileImport:$id] Tried to write failed file to disk, failed again. Sigh.", ex)
              }

              log.info(s"[bmFileImport:$id] Failure (ex) in ${clock.getMillis() - startMillis}ms")
              log.error(s"Could not import bookmark file for ${request.userId}, had an exception.", oops)
              BadRequest(s"""{"error": "couldnt_complete", "message": "${oops.getMessage}"}""")
          }
      case Left(err) =>
        log.info(s"[bmFileImport:$id] Failure (too large) in ${clock.getMillis() - startMillis}ms")
        log.warn(s"Could not import bookmark file for ${request.userId}, size too big: ${err.length}.\n${err.toString}")
        BadRequest(s"""{"error": "file_too_large", "size": ${err.length}}""")
    }
  }

  /* Parses Netscape-bookmark formatted file, extracting useful fields */
  def parseNetscapeBookmarks(bookmarks: File): Try[(Option[KeepSource], List[(String, String, List[String], Option[DateTime])])] = Try {
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
        val createdDate: Option[DateTime] = Option(elem.attr("add_date")).map(x => Try(new DateTime(x.toLong)).toOption).flatten

        // This may be useful in the future, but we currently are not using them:

        // val lastVisitDate = Option(elem.attr("last_visit"))

        (title, href, tagList, createdDate)
      }
    }.toList.flatten
    (source, extracted)
  }

  def createRawKeeps(userId: Id[User], source: Option[KeepSource], bookmarks: List[(String, String, List[Id[Collection]], Option[DateTime])], public: Boolean, libraryId: Option[Id[Library]]) = {
    val importId = UUID.randomUUID.toString
    val rawKeeps = bookmarks.map {
      case (title, href, tagIds, createdDate) =>
        val titleOpt = if (title.nonEmpty) Some(title) else None
        val tags = tagIds.map(_.id.toString).mkString(",") match {
          case s if s.isEmpty => None
          case s => Some(s)
        }

        RawKeep(userId = userId,
          title = titleOpt,
          url = href,
          isPrivate = !public,
          importId = Some(importId),
          source = source.getOrElse(KeepSource.bookmarkFileImport),
          originalJson = None,
          installationId = None,
          tagIds = tags,
          libraryId = libraryId,
          createdDate = createdDate)
    }
    (importId, rawKeeps)
  }

  def importFileToLibrary(pubId: PublicId[Library]) = UserAction(parse.maxLength(1024 * 1024 * 12, parse.temporaryFile)) { request =>
    val startMillis = clock.getMillis()
    val id = humanFriendlyToken(8)
    log.info(s"[bmFileImport:$id] Processing bookmark file import for ${request.userId}")

    request.body match {
      case Right(bookmarks) =>
        implicit val context = heimdalContextBuilderFactoryBean.withRequestInfoAndSource(request, KeepSource.bookmarkFileImport).build

        Try(bookmarks.file)
          .flatMap(parseNetscapeBookmarks)
          .map {
            case (sourceOpt, parsed) =>

              log.info(s"[bmFileImport:$id] Parsed in ${clock.getMillis() - startMillis}ms")
              val tagSet = scala.collection.mutable.Set.empty[String]
              parsed.foreach {
                case (_, _, tagsOpt, _) =>
                  tagsOpt.map { tagName =>
                    tagSet.add(tagName)
                  }
              }

              val importTag = sourceOpt match {
                case Some(source) => keepsCommander.getOrCreateTag(request.userId, "Imported from " + source.value)(context)
                case None => keepsCommander.getOrCreateTag(request.userId, "Imported links")(context)
              }
              val tags = tagSet.map { tagStr =>
                tagStr.trim -> timing(s"uploadBookmarkFile(${request.userId}) -- getOrCreateTag(${tagStr.trim})", 50) { keepsCommander.getOrCreateTag(request.userId, tagStr.trim)(context) }
              }.toMap
              val taggedKeeps = parsed.map {
                case (t, h, tagNames, createdDate) =>
                  val keepTags = tagNames.map(tags.get).flatten.map(_.id.get) :+ importTag.id.get
                  (t, h, keepTags, createdDate)
              }
              log.info(s"[bmFileImport:$id] Tags extracted in ${clock.getMillis() - startMillis}ms")

              val (importId, rawKeeps) = Library.decodePublicId(pubId) match {
                case Failure(ex) =>
                  // airbrake.notify(s"importing to library with invalid pubId ${pubId}")
                  throw new Exception(s"importing to library with invalid pubId ${pubId}")
                  ("", List.empty[RawKeep])
                case Success(id) =>
                  createRawKeeps(request.userId, sourceOpt, taggedKeeps, true, Some(id))
              }

              log.info(s"[bmFileImport:$id] Raw keep start persisting in ${clock.getMillis() - startMillis}ms")
              keepInterner.persistRawKeeps(rawKeeps, Some(importId))

              log.info(s"[bmFileImport:$id] Raw keep finished persisting in ${clock.getMillis() - startMillis}ms")

              (rawKeeps.length, tags.size)
          } match {
            case Success((keepSize, tagSize)) =>
              log.info(s"[bmFileImport:$id] Done in ${clock.getMillis() - startMillis}ms")
              log.info(s"Successfully processed bookmark file import for ${request.userId}. $keepSize keeps processed, $tagSize tags.")
              Ok(s"""{"done": "kindly let the user know that it is working and may take a sec", "count": $keepSize}""")
            case Failure(oops) =>
              // todo(Andrew): Remove this soon, or anonymize it. This is here to isolate some issues.
              val file: File = new File(s"bad-bookmarks-${request.userId}.html")
              try {
                FileUtils.copyFile(bookmarks.file, file)
              } catch {
                case ex: Throwable =>
                  log.error("[bmFileImport:$id] Tried to write failed file to disk, failed again. Sigh.", ex)
              }

              log.info(s"[bmFileImport:$id] Failure (ex) in ${clock.getMillis() - startMillis}ms")
              log.error(s"Could not import bookmark file for ${request.userId}, had an exception.", oops)
              BadRequest(s"""{"error": "couldnt_complete", "message": "${oops.getMessage}"}""")
          }
      case Left(err) =>
        log.info(s"[bmFileImport:$id] Failure (too large) in ${clock.getMillis() - startMillis}ms")
        log.warn(s"Could not import bookmark file for ${request.userId}, size too big: ${err.length}.\n${err.toString}")
        BadRequest(s"""{"error": "file_too_large", "size": ${err.length}}""")
    }
  }

}
