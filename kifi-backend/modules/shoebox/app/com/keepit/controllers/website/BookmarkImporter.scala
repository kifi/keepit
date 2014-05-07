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
          parsed.foreach { case (_, _, tagOpt) =>
            if (tagOpt.isDefined) {
              tagSet.add(tagOpt.get)
            }
          }
          val tags = tagSet.map { tagStr => keepsCommander.getOrCreateTag(request.userId, tagStr.trim)(context) }
          val taggedKeeps = parsed.map { case (t, h, tg) =>
            (t, h, tags.find(t => tg.isDefined && t.name == tg.get.trim).map(_.id.get))
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

  def parseNetscapeBookmarks(bookmarks: File): Try[List[(String, String, Option[String])]] = Try {
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
        val tagOpt = Option(elem.attr("list"))

        // These may be useful in the future, but we currently are not using them:
        // val createdDate = Option(elem.attr("add_date"))
        // val lastVisitDate = Option(elem.attr("last_visit"))

        (title, href, tagOpt)
      }
    }.toList.flatten
  }

  def createRawKeeps(userId: Id[User], bookmarks: List[(String, String, Option[Id[Collection]])]) = {
    val importId = UUID.randomUUID.toString
    val rawKeeps = bookmarks.map { case (title, href, tagId) =>
      val titleOpt = if (title.length > 0) Some(title) else None
      RawKeep(userId = userId,
        title = titleOpt,
        url = href,
        isPrivate = true,
        importId = Some(importId),
        source = KeepSource.bookmarkFileImport,
        originalJson = None,
        installationId = None,
        tagId = tagId)
    }
    (importId, rawKeeps)
  }


}
