package com.keepit.commanders.gen

import com.google.inject.Inject
import com.keepit.commanders.PathCommander
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.logging.AccessLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.slack.SlackInfoCommander

import scala.concurrent.duration.Duration

class BasicLibraryGen @Inject() (
    libRepo: LibraryRepo,
    orgRepo: OrganizationRepo,
    basicUserRepo: BasicUserRepo,
    basicLibraryCache: BasicLibraryByIdCache,
    slackInfoCommander: SlackInfoCommander,
    pathCommander: PathCommander,
    implicit val publicIdConfig: PublicIdConfiguration) {

  def getBasicLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], BasicLibrary] = {
    basicLibraryCache.bulkGetOrElse(libIds.map(BasicLibraryByIdKey)) { missingKeys =>
      generateBasicLibraries(missingKeys.map(_.libraryId)).map { case (libId, basicLibrary) => BasicLibraryByIdKey(libId) -> basicLibrary }
    }.map { case (BasicLibraryByIdKey(libId), basicLibrary) => libId -> basicLibrary }
  }

  def getBasicLibrary(libId: Id[Library])(implicit session: RSession): Option[BasicLibrary] = getBasicLibraries(Set(libId)).get(libId)

  private def generateBasicLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], BasicLibrary] = {
    val libById = libRepo.getActiveByIds(libIds)
    val orgById = {
      val orgSet = libById.values.flatMap(_.organizationId).toSet
      orgRepo.getByIds(orgSet)
    }
    val userById = {
      val userSet = libById.values.map(_.ownerId).toSet
      basicUserRepo.loadAllActive(userSet)
    }
    val slackInfoById = slackInfoCommander.getLiteSlackInfoForLibraries(libIds)
    libById.flatMapValues { lib =>
      val spaceHandle = lib.organizationId.fold[Option[Handle]](userById.get(lib.ownerId).map(_.username))(orgId => orgById.get(orgId).map(_.handle))
      spaceHandle.map(handle => pathCommander.libPageByHandleAndSlug(handle, lib.slug)).map { libPath =>
        BasicLibrary(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          path = libPath.relativeWithLeadingSlash,
          visibility = lib.visibility,
          color = lib.color,
          slack = slackInfoById.get(lib.id.get)
        )
      }
    }
  }
}

case class BasicLibraryByIdKey(libraryId: Id[Library]) extends Key[BasicLibrary] {
  override val version = 1
  val namespace = "basic_library_by_id"
  def toKey(): String = libraryId.id.toString
}

class BasicLibraryByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicLibraryByIdKey, BasicLibrary](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
