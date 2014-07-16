import com.google.inject.Inject
import com.keepit.commanders.{ BasicLibraryIdKey, BasicLibraryIdCache, BasicLibrary }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model._

class LibraryInfoRepo @Inject() (libraryInfoRepo: LibraryInfoRepo,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    basicLibraryCache: BasicLibraryIdCache) {

  def load(libraryId: Id[Library])(implicit session: RSession): BasicLibrary = basicLibraryCache.getOrElse(BasicLibraryIdKey(libraryId)) {
    val targetLib = libraryRepo.get(libraryId)
    BasicLibrary.fromLibraryAndOwner(targetLib, userRepo.get(targetLib.ownerId))
  }

  def loadAll(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], BasicLibrary] = {
    basicLibraryCache.bulkGetOrElse(libraryIds.map { BasicLibraryIdKey(_) }) { keys =>
      keys.map { k => (k -> load(k.libraryId)) }.toMap
    }.map { case (k, v) => (k.libraryId, v) }
  }
}
