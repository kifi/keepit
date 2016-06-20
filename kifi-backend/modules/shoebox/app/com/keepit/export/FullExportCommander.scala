package com.keepit.export

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.TagCommander
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.libs.iteratee.{ Enumeratee, Enumerator }

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
  eliza: ElizaServiceClient,
  clock: Clock,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FullExportCommander with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)
  import FullExportCommanderConfig._

  def fullExport(userId: Id[User]): FullStreamingExport.Root = {
    slackLog.info(s"[${clock.now}] Export for user $userId")
    val user = db.readOnlyMaster { implicit s => basicUserGen.load(userId) }
    val spaces = spacesExport(userId)
    FullStreamingExport.Root(user, spaces)
  }
  private def spacesExport(userId: Id[User]): Enumerator[FullStreamingExport.SpaceExport] = {
    // Should be small enough to fit into memory
    val spacesEnum: Enumerator[LibrarySpace] = {
      val spaces = db.readOnlyMaster { implicit s =>
        val orgSpaces = {
          val orgMemberships = orgMemberRepo.getAllByUserId(userId)
          orgMemberships.map(om => LibrarySpace.fromOrganizationId(om.organizationId))
        }

        val memberSpaces = {
          val libMemberships = libMemberRepo.getWithUserId(userId)
          libraryRepo.getActiveByIds(libMemberships.map(_.libraryId).toSet).values.map(_.space).toSeq
        }
        orgSpaces ++ memberSpaces
      }
      Enumerator(spaces.distinct: _*)
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
      val batch = db.readOnlyMaster { implicit s =>
        libraryRepo.pageBySpace(userId, space, offset = offset, limit = LIB_BATCH_SIZE)
      }
      if (batch.isEmpty) None else Some((offset + batch.length, batch))
    }

    batchedLibs through Enumeratee.mapConcat { libs =>
      libs.map(lib => FullStreamingExport.LibraryExport(lib, libKeepsExport(userId, lib.id.get)))
    }
  }
  private def libKeepsExport(userId: Id[User], libId: Id[Library]): Enumerator[FullStreamingExport.KeepExport] = {
    val batchedKeeps = Enumerator.unfold(0) { offset =>
      val batch = db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(libId, offset = offset, limit = KEEP_BATCH_SIZE) }
      if (batch.isEmpty) None else Some((offset + batch.length, batch))
    }
    val batchedExports = batchedKeeps.through(Enumeratee.mapM { keeps =>
      val keepIds = keeps.map(_.id.get).toSet
      val uriIds = keeps.map(_.uriId).toSet
      for {
        discussions <- eliza.getCrossServiceDiscussionsForKeeps(keepIds, fromTime = None, maxMessagesShown = 100)
        summaries <- rover.getUriSummaryByUris(uriIds)
      } yield keeps.map { keep =>
        FullStreamingExport.KeepExport(keep, discussions.get(keep.id.get), summaries.get(keep.uriId))
      }
    })
    batchedExports.through(Enumeratee.mapConcat(identity))
  }
}
