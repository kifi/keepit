import com.google.inject.Inject
import com.keepit.commanders.{ LibraryInfoIdKey, LibraryInfoIdCache, LibraryInfo }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model._

class LibraryInfoRepo @Inject() (libraryInfoRepo: LibraryInfoRepo,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryInfoCache: LibraryInfoIdCache) {

  def load(libraryId: Id[Library])(implicit session: RSession): LibraryInfo = libraryInfoCache.getOrElse(LibraryInfoIdKey(libraryId)) {
    val targetLib = libraryRepo.get(libraryId)
    LibraryInfo.fromLibraryAndOwner(targetLib, userRepo.get(targetLib.ownerId))
  }

  def loadAll(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], LibraryInfo] = {
    libraryInfoCache.bulkGetOrElse(libraryIds.map { LibraryInfoIdKey(_) }) { keys =>
      keys.map { k => (k -> load(k.libraryId)) }.toMap
    }.map { case (k, v) => (k.libraryId, v) }
  }
}
