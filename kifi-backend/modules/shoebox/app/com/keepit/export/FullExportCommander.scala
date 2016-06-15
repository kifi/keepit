package com.keepit.export

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.TagCommander
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.libs.iteratee.{ Enumeratee, Enumerator }

import scala.collection.mutable
import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[FullExportCommanderImpl])
trait FullExportCommander {
  def fullExport(userId: Id[User]): FullStreamingExport.Root
}

object FullExportCommanderConfig {
  val LIB_MEMBERSHIP_BATCH_SIZE = 100
  val LIB_BATCH_SIZE = 100
  val KEEP_BATCH_SIZE = 100
  val URI_SUMMARY_BATCH_SIZE = 100
}

@Singleton
class FullExportCommanderImpl @Inject() (
  db: Database,
  basicUserGen: BasicUserRepo,
  basicOrgGen: BasicOrganizationGen,
  orgMemberRepo: OrganizationMembershipRepo,
  libMemberRepo: LibraryMembershipRepo,
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  libraryRepo: LibraryRepo,
  tagCommander: TagCommander,
  rover: RoverServiceClient,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FullExportCommander with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)
  import FullExportCommanderConfig._

  def fullExport(userId: Id[User]): FullStreamingExport.Root = {
    val user = db.readOnlyMaster { implicit s => basicUserGen.load(userId) }
    val spaces = spacesExport(userId)
    FullStreamingExport.Root(user, spaces)
  }
  private def spacesExport(userId: Id[User]): Enumerator[FullStreamingExport.SpaceExport] = {
    // Should be small enough to fit into memory easily
    val seen: mutable.Set[LibrarySpace] = mutable.Set.empty
    def uniqify(s: LibrarySpace): Option[LibrarySpace] = if (!seen.contains(s)) {
      seen.add(s)
      Some(s)
    } else None

    val spacesEnum: Enumerator[LibrarySpace] = {
      val orgMemberships = db.readOnlyMaster { implicit s => orgMemberRepo.getAllByUserId(userId) }
      Enumerator.enumerate(orgMemberships.flatMap { om =>
        uniqify(LibrarySpace.fromOrganizationId(om.organizationId))
      })
    }.andThen {
      Enumerator.unfold(0) { offset =>
        val batch = db.readOnlyMaster { implicit s =>
          val libMemberships = libMemberRepo.pageByUserId(userId, offset = offset, limit = LIB_MEMBERSHIP_BATCH_SIZE)
          libraryRepo.getActiveByIds(libMemberships.map(_.libraryId).toSet)
        }.values.map(_.space).toSeq
        if (batch.isEmpty) None else Some((offset + batch.length, batch))
      }.through(Enumeratee.mapConcat(_.flatMap(uniqify)))
    }

    val polisher: Enumeratee[LibrarySpace, FullStreamingExport.SpaceExport] = Enumeratee.mapConcat { space =>
      val entity = db.readOnlyMaster { implicit s =>
        space match {
          case LibrarySpace.UserSpace(uId) => basicUserGen.loadActive(uId).map(Left(_))
          case LibrarySpace.OrganizationSpace(oId) => basicOrgGen.getBasicOrganizationHelper(oId).map(Right(_))
        }
      }
      entity.map(e => FullStreamingExport.SpaceExport(e, librariesExport(userId, space))).toSeq
    }
    spacesEnum.through(polisher)
  }

  private def librariesExport(userId: Id[User], space: LibrarySpace): Enumerator[FullStreamingExport.LibraryExport] = {
    val batchedLibs = Enumerator.unfold(0) { offset =>
      val batch = db.readOnlyMaster { implicit s => libraryRepo.pageBySpace(space, offset = offset, limit = LIB_BATCH_SIZE) }
      if (batch.isEmpty) None else Some((offset + batch.length, batch))
    }

    batchedLibs.through(Enumeratee.mapConcat { libs =>
      libs.map(lib => FullStreamingExport.LibraryExport(lib, libKeepsExport(userId, lib.id.get)))
    })
  }
  private def libKeepsExport(userId: Id[User], libId: Id[Library]): Enumerator[FullStreamingExport.KeepExport] = {
    val batchedKeeps = Enumerator.unfold(0) { offset =>
      val batch = db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(libId, offset = offset, limit = KEEP_BATCH_SIZE) }
      if (batch.isEmpty) None else Some((offset + batch.length, batch))
    }
    val batchedExports = batchedKeeps.through(Enumeratee.mapM { keeps =>
      rover.getUriSummaryByUris(keeps.map(_.uriId).toSet).imap { summaries =>
        keeps.map(keep => FullStreamingExport.KeepExport(keep, summaries.get(keep.uriId)))
      }
    })
    batchedExports.through(Enumeratee.mapConcat(identity))
  }
}
