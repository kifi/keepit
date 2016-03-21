package com.keepit.typeahead

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.{ BinaryCacheImpl, CacheStatistics, FortyTwoCachePlugin, Key }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3Bucket
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.typeahead.LibraryTypeahead.UserLibraryTypeahead
import org.joda.time.Minutes

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object LibraryTypeahead {
  type UserLibraryTypeahead = PersonalTypeahead[User, Library, LibraryData]
}

class LibraryTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    store: LibraryTypeaheadStore,
    cache: LibraryTypeaheadCache,
    basicUserRepo: BasicUserRepo,
    organizationAvatarCommander: OrganizationAvatarCommander,
    libraryMembershipCommander: LibraryMembershipCommander,
    libraryMembershipRepo: LibraryMembershipRepo,
    libPathCommander: PathCommander,
    libraryInfoCommander: LibraryInfoCommander,
    libraryRepo: LibraryRepo,
    implicit val ec: ExecutionContext,
    implicit val config: PublicIdConfiguration) extends Typeahead[User, Library, LibraryData, UserLibraryTypeahead] with Logging {

  protected val refreshRequestConsolidationWindow = 10 minutes

  protected val fetchRequestConsolidationWindow = 15 seconds

  protected def get(userId: Id[User]) = {
    val filterOpt = cache.getOrElseOpt(LibraryTypeaheadKey(userId)) {
      store.getWithMetadata(userId).map {
        case (filter, meta) =>
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=$currentDateTime); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId)
          }
          filter
      }
    }
    Future.successful(filterOpt.map(filter => PersonalTypeahead(userId, filter, getInfos(userId))))
  }

  private def getInfos(userId: Id[User])(ids: Seq[Id[Library]]): Future[Seq[LibraryData]] = {
    if (ids.isEmpty) Future.successful(Seq.empty[LibraryData])
    else {
      SafeFuture {
        val idSet = ids.toSet
        db.readOnlyReplica { implicit session =>
          val libs = libraryRepo.getActiveByIds(idSet)
          val memberships = libraryMembershipRepo.getWithLibraryIdsAndUserId(idSet, userId)
          val collaborators = libraryMembershipRepo.getCollaboratorsByLibrary(idSet)
          val libData = ids.flatMap { libId =>
            libs.get(libId).map { lib =>
              (lib, memberships.get(libId), collaborators.getOrElse(libId, Set.empty))
            }
          }
          buildData(userId, libData).map(_._2)
        }
      }
    }
  }

  private def getAllInfos(id: Id[User]): Future[Seq[(Id[Library], LibraryData)]] = SafeFuture {
    db.readOnlyReplica { implicit session =>
      buildData(id, libraryInfoCommander.getLibrariesUserCanKeepTo(id, includeOrgLibraries = true))
    }
  }

  private def buildData(userId: Id[User], items: Seq[(Library, Option[LibraryMembership], Set[Id[User]])])(implicit session: RSession): Seq[(Id[Library], LibraryData)] = {
    val allUserIds = items.flatMap(_._3).toSet
    val orgIds = items.map(_._1).flatMap(_.organizationId)

    val basicUserById = basicUserRepo.loadAll(allUserIds)
    val orgAvatarsById = organizationAvatarCommander.getBestImagesByOrgIds(orgIds.toSet, ProcessedImageSize.Medium.idealSize)

    items.map {
      case (lib, membership, collaboratorsIds) =>
        val collabs = (collaboratorsIds - userId).map(basicUserById(_)).toSeq
        val orgAvatarPath = lib.organizationId.flatMap { orgId => orgAvatarsById.get(orgId).map(_.imagePath) }
        val membershipInfo = membership.map { mem => libraryMembershipCommander.createMembershipInfo(mem) }

        lib.id.get -> LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          color = lib.color,
          visibility = lib.visibility,
          path = libPathCommander.getPathForLibrary(lib),
          hasCollaborators = collabs.nonEmpty,
          subscribedToUpdates = membership.exists(_.subscribedToUpdates),
          collaborators = collabs,
          orgAvatar = orgAvatarPath,
          membership = membershipInfo
        )
    }
  }

  protected def extractName(info: LibraryData): String = info.name

  protected def invalidate(typeahead: UserLibraryTypeahead) = {
    val userId = typeahead.ownerId
    val filter = typeahead.filter
    cache.set(LibraryTypeaheadKey(userId), filter)
    store += (userId -> filter)
  }

  protected def create(userId: Id[User]) = {
    getAllInfos(userId).map { allInfos =>
      val filter = buildFilter(userId, allInfos)
      log.info(s"[refresh($userId)] cache/store updated; filter=$filter")
      val allMap = allInfos.toMap
      def getter(libs: Seq[Id[Library]]) = Future.successful {
        libs.flatMap(allMap.get)
      }
      PersonalTypeahead(userId, filter, getter)
    }
  }
}

trait LibraryTypeaheadStore extends PrefixFilterStore[User, Library]

class S3LibraryTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User, Library](bucket, amazonS3Client, accessLog) with LibraryTypeaheadStore

class InMemoryLibraryTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User, Library] with LibraryTypeaheadStore

class LibraryTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[LibraryTypeaheadKey, PrefixFilter[Library]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[Library]] {
  val namespace = "library_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}

