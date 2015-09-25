package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.model.LibrarySpace.{ UserSpace, OrganizationSpace }
import com.keepit.model._
import com.keepit.common.time.dateTimeOrdering

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[KeepExportCommanderImpl])
trait KeepExportCommander {
  def assembleKeepExport(keepExports: Seq[KeepExport]): String
  def exportKeeps(exportRequest: KeepExportRequest): Future[Try[KeepExportResponse]]
}

@Singleton
class KeepExportCommanderImpl @Inject() (
    db: Database,
    keepRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    ktuRepo: KeepToUserRepo,
    ktcRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    permissionCommander: PermissionCommander,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends KeepExportCommander with Logging {

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

  private def unsafeExportKeeps(exportRequest: KeepExportRequest)(implicit session: RSession): KeepExportResponse = {
    val keepIds = exportRequest match {
      case PersonalKeepExportRequest(userId) =>
        val collaborativeLibIds = libraryMembershipRepo.getLibrariesWithWriteAccess(userId)
        def libIsInValidSpace(lib: Library): Boolean = lib.space match {
          case UserSpace(uid) => uid == userId
          case OrganizationSpace(_) => false
        }
        val libIdsToExportFrom = libraryRepo.getByIds(collaborativeLibIds).values.filter(libIsInValidSpace).map(_.id.get).toSet
        ktlRepo.getAllByLibraryIds(libIdsToExportFrom).values.flatten.filter(_.addedBy == userId).map(_.keepId)

      case OrganizationKeepExportRequest(userId, orgIds) =>
        Seq.empty
        val libIdsToExportFrom = orgIds.flatMap { orgId => libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId)) }.filter(!_.isSecret).map(_.id.get).toSet
        ktlRepo.getAllByLibraryIds(libIdsToExportFrom).values.flatten.map(_.keepId)
    }
    val keeps = keepRepo.getByIds(keepIds.toSet).values.toSeq
    val tagIds = ktcRepo.getCollectionsForKeeps(keeps)
    val idToTag = collectionRepo.getByIds(tagIds.flatten.toSet).mapValues(_.name.tag)
    val tagsByKeepId = (keeps zip tagIds).map { case (keep, keepTags) => keep.id.get -> keepTags.map(idToTag(_)) }.toMap
    KeepExportResponse(keeps.sortBy(_.keptAt), tagsByKeepId)
  }
}

