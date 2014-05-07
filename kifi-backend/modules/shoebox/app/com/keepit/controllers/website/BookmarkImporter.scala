package com.keepit.controllers.website


import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.commanders.{KeepsCommander, KeepInterner, UserCommander}
import com.keepit.common.controller.{WebsiteController, ShoeboxServiceController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.common.net.URI
import com.keepit.model._

import play.api.libs.json.{JsNumber, Json}

import com.google.inject.Inject
import scala.util.{Try, Failure, Success}
import org.jsoup.Jsoup
import java.io.File
import com.keepit.common.db.Id
import java.util.UUID
import com.keepit.heimdal.{HeimdalContextBuilderFactory, HeimdalContextBuilder}

class BookmarkImporter @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  rawKeepFactory: RawKeepFactory,
  keepInterner: KeepInterner,
  heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory,
  keepsCommander: KeepsCommander
)  extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {


  def uploadBookmarkFile() = JsonAction.authenticated(allowPending = true, parser = parse.maxLength(1024*1024*5, parse.temporaryFile)) { request =>
    request.body match {
      case Right(bookmarks) =>
        implicit val context = heimdalContextBuilderFactoryBean.withRequestInfoAndSource(request, KeepSource.bookmarkFileImport).build

        Try(bookmarks.file)
        .flatMap(parseNetscapeBookmarks)
        .map { parsed =>
          val tagSet = scala.collection.mutable.Set.empty[String]
          parsed.foreach { case (_, _, tagsOpt) =>
            tagsOpt.map { tagName =>
              tagSet.add(tagName)
            }
          }
          val tags = tagSet.map { tagStr => tagStr.trim -> keepsCommander.getOrCreateTag(request.userId, tagStr.trim)(context) }.toMap
          val taggedKeeps = parsed.map { case (t, h, tagNames) =>
            val keepTags = tagNames.map(tags.get).flatten.map(_.id.get)
            (t, h, keepTags)
          }
          val (importId, rawKeeps) = createRawKeeps(request.userId, taggedKeeps)

          keepInterner.persistRawKeeps(rawKeeps, Some(importId))
        } match {
          case Success(a) =>
            Ok(s"""{"done": "kindly let the user know that it is working and may take a sec"}""")
          case Failure(oops) =>
            BadRequest(s"""{"error": "couldnt_complete", "message": "${oops.getMessage}"}""")
        }
      case Left(err) =>
        BadRequest(s"""{"error": "file_too_large", "size": ${err.length}}""")
    }
  }

  def parseNetscapeBookmarks(bookmarks: File): Try[List[(String, String, List[String])]] = Try {
    // This is a standard for bookmark exports.
    // http://msdn.microsoft.com/en-us/library/aa753582(v=vs.85).aspx

    import scala.collection.JavaConversions._

    val parsed = Jsoup.parse(bookmarks, "UTF-8")

    val links = parsed.select("dt a")

    links.iterator().map { elem =>
      for {
        title <- Option(elem.text())
        href <- Option(elem.attr("href"))
      } yield {
        val tagsOpt = Option(elem.attr("list")).map(_.split(",").toList).getOrElse(List.empty)

        // These may be useful in the future, but we currently are not using them:
        // val createdDate = Option(elem.attr("add_date"))
        // val lastVisitDate = Option(elem.attr("last_visit"))

        (title, href, tagsOpt)
      }
    }.toList.flatten
  }

  def createRawKeeps(userId: Id[User], bookmarks: List[(String, String, List[Id[Collection]])]) = {
    val importId = UUID.randomUUID.toString
    val rawKeeps = bookmarks.map { case (title, href, tagIds) =>
      val titleOpt = if (title.length > 0) Some(title) else None
      val tags = tagIds.map(_.id.toString).mkString(",") match {
        case s if s.length == 0 => None
        case s => Some(s)
      }

      RawKeep(userId = userId,
        title = titleOpt,
        url = href,
        isPrivate = true,
        importId = Some(importId),
        source = KeepSource.bookmarkFileImport,
        originalJson = None,
        installationId = None,
        tagIds = tags)
    }
    (importId, rawKeeps)
  }


}
