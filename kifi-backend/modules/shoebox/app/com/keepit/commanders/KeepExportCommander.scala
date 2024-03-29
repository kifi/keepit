package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.{ anyExtensionOps, futureExtensionOps, mapExtensionOps, optionExtensionOps }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.time.dateTimeOrdering
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.RoverUriSummary
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.libs.iteratee.{ Concurrent, Enumerator }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[KeepExportCommanderImpl])
trait KeepExportCommander {
  def assembleKeepExport(keepExports: Seq[KeepExport]): String
  def exportKeeps(exportRequest: KeepExportRequest): Future[Try[KeepExportResponse]]
  def fullKifiExport(userId: Id[User]): Enumerator[(String, String)]
}

@Singleton
class KeepExportCommanderImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  permissionCommander: PermissionCommander,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  tagCommander: TagCommander,
  rover: RoverServiceClient,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepExportCommander with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  def assembleKeepExport(keepExports: Seq[KeepExport]): String = {
    // HTML format that follows Delicious exports
    val before = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
                   |<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
                   |<!--This is an automatically generated file.
                   |It will be read and overwritten.
                   |Do Not Edit! -->
                   |<Title>Kifi Bookmarks Export</Title>
                   |<H1>Bookmarks</H1>
                   |<DL>
                   |""".stripMargin
    val after = "\n</DL>"

    def createExport(keep: KeepExport): String = {
      // Parse Tags
      val title = keep.title getOrElse ""
      val tagString = keep.tags map { tags =>
        s""" TAGS="${tags.replace("&", "&amp;").replace("\"", "")}""""
      } getOrElse ""
      val date = keep.createdAt.getMillis() / 1000
      val line =
        s"""<DT><A HREF="${keep.url}" ADD_DATE="${date}"${tagString}>${title.replace("&", "&amp;")}</A>"""
      line
    }
    before + keepExports.map(createExport).mkString("\n") + after
  }

  private def validateRequest(req: KeepExportRequest)(implicit session: RSession): Option[OrganizationFail] = {
    req match {
      case PersonalKeepExportRequest(_) => None
      case OrganizationKeepExportRequest(userId, orgIds) =>
        val hasAllNecessaryExportPermissions = orgIds.forall(orgId => permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(OrganizationPermission.EXPORT_KEEPS))
        if (!hasAllNecessaryExportPermissions) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None
    }
  }

  def exportKeeps(exportRequest: KeepExportRequest): Future[Try[KeepExportResponse]] = db.readOnlyReplicaAsync { implicit session =>
    val validationError = validateRequest(exportRequest)
    validationError match {
      case Some(fail) => Failure(fail)
      case None =>
        Success(unsafeExportKeeps(exportRequest))
    }
  }

  def unsafeExportKeeps(exportRequest: KeepExportRequest)(implicit session: RSession): KeepExportResponse = {
    val keepIds = exportRequest match {
      case PersonalKeepExportRequest(userId) =>
        val writableLibIds = libraryMembershipRepo.getLibrariesWithWriteAccess(userId)
        def libIsInValidSpace(lib: Library): Boolean = lib.space match {
          case UserSpace(uid) => uid == userId
          case OrganizationSpace(_) => false
        }
        val libIdsToExportFrom = libraryRepo.getActiveByIds(writableLibIds).values.filter(libIsInValidSpace).map(_.id.get).toSet
        val ktlsByLibrary = ktlRepo.getAllByLibraryIds(libIdsToExportFrom)
        ktlsByLibrary.values.flatten.filter(_.addedBy.safely.contains(userId)).map(_.keepId).toSet tap { keepIds =>
          slackLog.info(s"Exporting ${keepIds.size} personal keeps from ${libIdsToExportFrom.size} libs for user $userId")
        }

      case OrganizationKeepExportRequest(userId, orgIds) =>
        val libIdsToExportFrom = orgIds.flatMap { orgId => libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId)) }.filter(!_.isSecret).map(_.id.get)
        ktlRepo.getAllByLibraryIds(libIdsToExportFrom).values.flatten.map(_.keepId).toSet tap { keepIds =>
          slackLog.info(s"Exporting ${keepIds.size} org keeps from ${libIdsToExportFrom.size} libs for user $userId")
        }
    }
    val keeps = keepRepo.getActiveByIds(keepIds).values.toSeq
    val tagsByKeepId = {
      val tags = tagCommander.getTagsForKeeps(keeps.flatMap(_.id))
      keeps.map { keep => keep.id.get -> tags(keep.id.get).map(_.tag) }.toMap
    }

    val libIdsByKeep = ktlRepo.getAllByKeepIds(keeps.map(_.id.get).toSet).mapValues(ktls => ktls.map(_.libraryId))
    val idToLib = libraryRepo.getActiveByIds(libIdsByKeep.values.flatten.toSet)
    val libsByKeepId = keeps.map { keep => keep.id.get -> libIdsByKeep(keep.id.get).flatMap(idToLib.get) }.toMap
    KeepExportResponse(keeps.sortBy(_.keptAt), tagsByKeepId, libsByKeepId)
  }

  type FileName = String
  type HtmlDump = String
  def fullKifiExport(userId: Id[User]): Enumerator[(FileName, HtmlDump)] = {
    val KEEP_BATCH_SIZE = 100
    def beginEmitting(channel: Concurrent.Channel[(FileName, HtmlDump)]): Unit = {
      val libs = db.readOnlyMaster { implicit s =>
        val allLibIds = libraryMembershipRepo.getWithUserId(userId).map(_.libraryId)
        libraryRepo.getActiveByIds(allLibIds.toSet)
      }
      channel.push(indexLink -> indexPage(libs))

      val keeps = db.readOnlyMaster { implicit s =>
        val keepIds = ktlRepo.getAllByLibraryIds(libs.keySet).values.flatMap(_.map(_.keepId)).toSet
        keepRepo.getActiveByIds(keepIds.toSet)
      }

      libs.foreach {
        case (lId, lib) =>
          channel.push(libraryLink(lib) -> libraryPage(lib, keeps))
      }

      FutureHelpers.sequentialExec(keeps.grouped(KEEP_BATCH_SIZE).toIterable) { batch =>
        rover.getUriSummaryByUris(batch.values.map(_.uriId).toSet).imap { summaries =>
          batch.foreach {
            case (kId, keep) =>
              channel.push(keepLink(keep) -> keepPage(keep, summaries.get(keep.uriId)))
          }
        }
      }.andThen {
        case result =>
          channel.eofAndEnd()
      }
    }

    Concurrent.unicast(onStart = beginEmitting)
  }

  private def indexLink = "index.html"
  private def indexPage(libs: Map[Id[Library], Library]): HtmlDump = {
    s"""
       |<h1>Kifi Export</h1>
       |<ul>
       |${libs.traverseByKey.map(l => s"<li><a href=${libraryLink(l)}>${l.name}</a></li>").mkString("\n")}
       |</ul>
    """.stripMargin
  }

  private def libraryLink(lib: Library) = s"${Library.publicId(lib.id.get).id}.html"
  private def libraryPage(lib: Library, keeps: Map[Id[Keep], Keep]): HtmlDump = {
    val keepsList = keeps.filterValues(_.recipients.libraries.contains(lib.id.get)).traverseByKey.map { k =>
      s"<li><a href=${keepLink(k)}>${k.title getOrElse k.url}</a></li>"
    }.mkString("\n")
    s"""
       |<a href=$indexLink>Home</a>
       |<h1>${lib.name}</h1>
       |<h2>${lib.keepCount} keeps</h1>
       |<ul>
       |$keepsList
       |</ul>
    """.stripMargin
  }

  private def keepLink(keep: Keep) = s"${Keep.publicId(keep.id.get).id}.html"
  private def keepPage(keep: Keep, uriSummary: Option[RoverUriSummary]): HtmlDump = {
    s"""
       |<a href=$indexLink>Home</a>
       |<h1>${keep.title getOrElse keep.url}</h1>
       |${uriSummary.flatMap(_.article.description) getOrElse "<no_content>"}
    """.stripMargin
  }
}

