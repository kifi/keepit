package com.keepit.curator.model

import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent }
import com.keepit.common.db.Id
import com.keepit.model.{ Library, KeepStates, User, NormalizedURI, Keep }
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.{ RSession }

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.google.common.cache.{ CacheBuilder, Cache }

import scala.slick.jdbc.StaticQuery

import java.util.concurrent.{ TimeUnit, Callable }

@ImplementedBy(classOf[CuratorKeepInfoRepoImpl])
trait CuratorKeepInfoRepo extends DbRepo[CuratorKeepInfo] {
  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[CuratorKeepInfo]
  def getKeepersByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[User]]
  def checkDiscoverableByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def getUserURIsAndKeeps(userId: Id[User])(implicit session: RSession): Seq[(Id[NormalizedURI], Id[Keep])]
  def getUsersWithKeepsCounts()(implicit session: RSession): Seq[(Id[User], Int)]
  def getKeepCountForUser(userId: Id[User])(implicit session: RSession): Int
}

@Singleton
class CuratorKeepInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CuratorKeepInfo] with CuratorKeepInfoRepo {

  import db.Driver.simple._

  type RepoImpl = CuratorKeepInfoTable
  class CuratorKeepInfoTable(tag: Tag) extends RepoTable[CuratorKeepInfo](db, tag, "curator_keep_info") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.Nullable)
    def discoverable = column[Boolean]("discoverable", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriId, userId, keepId, libraryId.?, state, discoverable) <> ((CuratorKeepInfo.apply _).tupled, CuratorKeepInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorKeepInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorKeepInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorKeepInfo)(implicit session: RSession): Unit = {}

  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[CuratorKeepInfo] = {
    (for (row <- rows if row.keepId === keepId) yield row).firstOption
  }

  private val keeperByUriIdCache: Cache[Id[NormalizedURI], Seq[Id[User]]] = CacheBuilder.newBuilder().concurrencyLevel(4).initialCapacity(1000).maximumSize(10000).expireAfterWrite(60, TimeUnit.SECONDS).build()

  def getKeepersByUriIdCompiled(uriId: Column[Id[NormalizedURI]]) =
    Compiled { (for (row <- rows if row.uriId === uriId && row.state === CuratorKeepInfoStates.ACTIVE) yield row.userId) }

  def getKeepersByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[User]] = {
    keeperByUriIdCache.get(uriId, new Callable[Seq[Id[User]]] {
      def call() = getKeepersByUriIdCompiled(uriId).list
    })
  }

  def checkDiscoverableByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (row <- rows if row.uriId === uriId && row.state === CuratorKeepInfoStates.ACTIVE && row.discoverable) yield row.id).firstOption.isDefined
  }

  def getUserURIsAndKeeps(userId: Id[User])(implicit session: RSession): Seq[(Id[NormalizedURI], Id[Keep])] = {
    (for (r <- rows if r.userId === userId && r.state === CuratorKeepInfoStates.ACTIVE) yield (r.uriId, r.keepId)).list
  }

  def getUsersWithKeepsCounts()(implicit session: RSession): Seq[(Id[User], Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"SELECT user_id, COUNT(*) FROM curator_keep_info WHERE state='active' GROUP BY user_id".as[(Id[User], Int)].list
  }

  def getKeepCountForUser(userId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"SELECT COUNT(*) FROM curator_keep_info WHERE state='active' AND user_id=$userId".as[Int].firstOption.getOrElse(0)
  }

}
