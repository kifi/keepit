package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton, Provider }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ SequenceNumber, DbSequenceAssigner, Id, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.slick.jdbc.{ GetResult, PositionedResult, StaticQuery }

@ImplementedBy(classOf[LibraryRepoImpl])
trait LibraryRepo extends Repo[Library] with SeqNumberFunction[Library] {
  def getByNameAndUserId(userId: Id[User], name: String, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getByUser(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE), excludeAccess: Option[LibraryAccess] = None)(implicit session: RSession): Seq[(LibraryMembership, Library)]
  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): List[Library]
  def getBySlugAndUserId(userId: Id[User], slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getByNameOrSlug(userId: Id[User], name: String, slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getOpt(ownerId: Id[User], slug: LibrarySlug)(implicit session: RSession): Option[Library]
  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession): Unit
  def getLibraries(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library]
  def countLibrariesOfUserFromAnonymous(userId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int
  def countLibrariesToSelf(userId: Id[User])(implicit session: RSession): Int
  def countLibrariesForOtherUser(userId: Id[User], friendId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int
  def getLibrariesOfUserFromAnonymous(ownerId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getOwnedLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesOfSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesFromAnonymous(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getLibrariesOfSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getAllPublishedLibraries()(implicit session: RSession): Seq[Library]
  def getNewPublishedLibraries(size: Int = 20)(implicit session: RSession): Seq[Library]
  def pagePublished(page: Paginator)(implicit session: RSession): Seq[Library]
  def countPublished(implicit session: RSession): Int
  def filterPublishedByMemberCount(minCount: Int, limit: Int = 100)(implicit session: RSession): Seq[Library]
}

@Singleton
class LibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryMembershipRepo: Provider[LibraryMembershipRepoImpl],
  val libraryMetadataCache: LibraryMetadataCache,
  val idCache: LibraryIdCache)
    extends DbRepo[Library] with LibraryRepo with SeqNumberDbFunction[Library] with Logging {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  type RepoImpl = LibraryTable
  class LibraryTable(tag: Tag) extends RepoTable[Library](db, tag, "library") with SeqNumberColumn[Library] {
    def name = column[String]("name", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def slug = column[LibrarySlug]("slug", O.NotNull)
    def color = column[HexColor]("color", O.Nullable)
    def kind = column[LibraryKind]("kind", O.NotNull)
    def universalLink = column[String]("universal_link", O.NotNull)
    def memberCount = column[Int]("member_count", O.NotNull)
    def lastKept = column[Option[DateTime]]("last_kept", O.Nullable)

    def * = (id.?, createdAt, updatedAt, name, ownerId, visibility, description, slug, color.?, state, seq, kind, universalLink, memberCount, lastKept) <> ((Library.applyFromDbRow _).tupled, Library.unapply)
  }

  def table(tag: Tag) = new LibraryTable(tag)
  initTable()

  private implicit val getLibraryResult: GetResult[com.keepit.model.Library] = GetResult { r: PositionedResult =>
    Library.applyFromDbRow(
      id = r.<<[Option[Id[Library]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      name = r.<<[String],
      ownerId = r.<<[Id[User]],
      visibility = r.<<[LibraryVisibility],
      description = r.<<[Option[String]],
      slug = LibrarySlug(r.<<[String]),
      color = r.<<[Option[String]].map(HexColor(_)),
      state = r.<<[State[Library]],
      seq = r.<<[SequenceNumber[Library]],
      kind = LibraryKind(r.<<[String]),
      universalLink = r.<<[String],
      memberCount = r.<<[Int],
      lastKept = r.<<[Option[DateTime]]
    )
  }

  override def save(library: Library)(implicit session: RWSession): Library = {
    val toSave = library.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def get(id: Id[Library])(implicit session: RSession): Library = {
    idCache.getOrElse(LibraryIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(library: Library)(implicit session: RSession): Unit = {
    library.id.map { id =>
      libraryMetadataCache.remove(LibraryMetadataKey(id))
      idCache.remove(LibraryIdKey(id))
    }
  }

  override def invalidateCache(library: Library)(implicit session: RSession): Unit = {
    library.id.map { id =>
      libraryMetadataCache.remove(LibraryMetadataKey(id))
      if (library.state == LibraryStates.INACTIVE) {
        deleteCache(library)
      } else {
        idCache.set(LibraryIdKey(id), library)
      }
    }
  }

  private val getByNameAndUserCompiled = Compiled { (userId: Column[Id[User]], name: Column[String]) =>
    (for (b <- rows if b.name === name && b.ownerId === userId) yield b)
  }
  private val getByNameAndUserWithExcludeCompiled = Compiled { (userId: Column[Id[User]], name: Column[String], excludeState: Column[State[Library]]) =>
    (for (b <- rows if b.name === name && b.ownerId === userId && b.state =!= excludeState) yield b)
  }
  def getByNameAndUserId(userId: Id[User], name: String, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library] = {
    excludeState match {
      case None => getByNameAndUserCompiled(userId, name).firstOption
      case Some(exclude) => getByNameAndUserWithExcludeCompiled(userId, name, exclude).firstOption
    }
  }

  private val getBySlugAndUserCompiled = Compiled { (userId: Column[Id[User]], slug: Column[LibrarySlug]) =>
    (for (b <- rows if b.slug === slug && b.ownerId === userId) yield b)
  }
  private val getBySlugAndUserWithExcludeCompiled = Compiled { (userId: Column[Id[User]], slug: Column[LibrarySlug], excludeState: Column[State[Library]]) =>
    (for (b <- rows if b.slug === slug && b.ownerId === userId && b.state =!= excludeState) yield b)
  }
  def getBySlugAndUserId(userId: Id[User], slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library] = {
    excludeState match {
      case None => getBySlugAndUserCompiled(userId, slug).firstOption
      case Some(exclude) => getBySlugAndUserWithExcludeCompiled(userId, slug, exclude).firstOption
    }
  }

  private val getByNameOrSlugCompiled = Compiled { (userId: Column[Id[User]], name: Column[String], slug: Column[LibrarySlug]) =>
    (for (b <- rows if (b.name === name || b.slug === slug) && b.ownerId === userId) yield b)
  }
  private val getByNameOrSlugWithExcludeCompiled = Compiled { (userId: Column[Id[User]], name: Column[String], slug: Column[LibrarySlug], excludeState: Column[State[Library]]) =>
    (for (b <- rows if (b.name === name || b.slug === slug) && b.ownerId === userId && b.state =!= excludeState) yield b)
  }
  def getByNameOrSlug(userId: Id[User], name: String, slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library] = {
    excludeState match {
      case None => getByNameOrSlugCompiled(userId, name, slug).firstOption
      case Some(exclude) => getByNameOrSlugWithExcludeCompiled(userId, name, slug, exclude).firstOption
    }
  }

  def getByUser(userId: Id[User], excludeState: Option[State[Library]], excludeAccess: Option[LibraryAccess])(implicit session: RSession): Seq[(LibraryMembership, Library)] = {
    val q = for {
      lib <- rows if lib.state =!= excludeState.orNull
      lm <- libraryMembershipRepo.get.rows if lm.libraryId === lib.id && lm.userId === userId && lm.access =!= excludeAccess.orNull && lm.state === LibraryMembershipStates.ACTIVE
    } yield (lm, lib)
    q.list
  }

  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): List[Library] = {
    (for { t <- rows if t.ownerId === ownerId && t.state =!= excludeState.orNull } yield t).list()
  }

  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession) = {
    val updateTime = Some(clock.now)
    (for { t <- rows if t.id === libraryId } yield (t.lastKept)).update(updateTime)
    invalidateCache(get(libraryId).copy(lastKept = updateTime))
  }

  private val getOptCompiled = Compiled { (ownerId: Column[Id[User]], slug: Column[LibrarySlug]) =>
    (for (r <- rows if r.ownerId === ownerId && r.slug === slug) yield r)
  }
  def getOpt(ownerId: Id[User], slug: LibrarySlug)(implicit session: RSession): Option[Library] = {
    getOptCompiled(ownerId, slug).firstOption
  }

  def getLibraries(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library] = {
    if (libraryIds.isEmpty) {
      Map.empty
    } else {
      idCache.bulkGetOrElse(libraryIds.map(LibraryIdKey(_))) { missingKeys =>
        (for (r <- rows if r.id.inSet(libraryIds)) yield r).list.map(library => LibraryIdKey(library.id.get) -> library).toMap
      }.map { case (libraryKey, library) => libraryKey.id -> library }
    }
  }

  //non user: number of public libraries that are “displayable on profile” (see library pref) plus libraries i follow that are public unless I oped in the "don't show libraries I follow" pref
  def countLibrariesOfUserFromAnonymous(userId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val query = if (countFollowLibraries) {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published'"
    } else {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published' and lm.access='owner'"
    }
    query.as[Int].firstOption.getOrElse(0)
  }

  //my own profile view: total number of libraries I own and I follow, including main and secret, not including pending invites to libs
  def countLibrariesToSelf(userId: Id[User])(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val query = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active'"
    query.as[Int].firstOption.getOrElse(0)
  }

  //logged in user viewing another’s profile: Everything in countLibrariesOfUserFromAnonymos + libraries user has access to (even if private)
  def countLibrariesForOtherUser(userId: Id[User], friendId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $userId and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = if (countFollowLibraries) {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and ((lm.listed and lib.visibility = 'published') #$libVisibility)"
    } else {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and ((lm.listed and lib.visibility = 'published' and lm.access='owner') #$libVisibility)"
    }
    query.as[Int].firstOption.getOrElse(0)
  }

  def getLibrariesOfUserFromAnonymous(ownerId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    val q = (for {
      lib <- rows if lib.ownerId === ownerId && lib.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && lib.state === LibraryStates.ACTIVE
      lm <- libraryMembershipRepo.get.rows if lm.libraryId === lib.id && lm.userId === ownerId && lm.listed && lm.state === LibraryMembershipStates.ACTIVE
    } yield lib).sortBy(x => (x.memberCount.desc, x.lastKept.desc, x.id.desc)).drop(page.itemsToDrop).take(page.size)
    q.list
  }

  //logged in user viewing another’s profile: Everything in countLibrariesOfUserFromAnonymos + libraries user has access to (even if private)
  def getOwnedLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import StaticQuery.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $userId and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.access = 'owner' and ((lm.listed and lib.visibility = 'published') #$libVisibility) order by lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getFollowingLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import StaticQuery.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.access != 'owner' and ((lm.listed and lib.visibility = 'published' and lm.access != 'owner') #$libVisibility) order by lm.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getFollowingLibrariesOfSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import StaticQuery.interpolation
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.access != 'owner' order by lm.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getFollowingLibrariesFromAnonymous(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import StaticQuery.interpolation
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published' and lm.access != 'owner' order by lm.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getLibrariesOfSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    (for (r <- rows if r.ownerId === userId && r.state === LibraryStates.ACTIVE) yield r).sortBy(x => (x.kind, x.memberCount.desc, x.lastKept.desc, x.id.desc)).drop(page.itemsToDrop).take(page.size).list
  }

  def getAllPublishedLibraries()(implicit session: RSession): Seq[Library] = {
    (for (r <- rows if r.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && r.state === LibraryStates.ACTIVE) yield r).list
  }

  def getNewPublishedLibraries(size: Int = 20)(implicit session: RSession): Seq[Library] = {
    (for (r <- rows if r.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && r.state === LibraryStates.ACTIVE) yield r).sortBy(_.id.desc).take(size).list
  }

  def pagePublished(page: Paginator = Paginator.fromStart(20))(implicit session: RSession): Seq[Library] = {
    val q = for {
      t <- rows if t.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && t.state === LibraryStates.ACTIVE
    } yield t
    q.sortBy(_.id desc).drop(page.itemsToDrop).take(page.size).list
  }

  def countPublished(implicit session: RSession): Int = {
    Query(
      (for { t <- rows if t.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && t.state === LibraryStates.ACTIVE } yield t).length
    ).first
  }

  def filterPublishedByMemberCount(minCount: Int, limit: Int = 100)(implicit session: RSession): Seq[Library] = {
    (for {
      t <- rows if t.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && t.state === LibraryStates.ACTIVE && t.memberCount >= minCount
    } yield t).sortBy(_.updatedAt.desc).take(limit).list
  }
}

trait LibrarySequencingPlugin extends SequencingPlugin

class LibrarySequencingPluginImpl @Inject() (
    override val actor: ActorInstance[LibrarySequencingActor],
    override val scheduling: SchedulingProperties) extends LibrarySequencingPlugin {

  override val interval: FiniteDuration = 20.seconds
}

@Singleton
class LibrarySequenceNumberAssigner @Inject() (db: Database, repo: LibraryRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[Library](db, repo, airbrake)

class LibrarySequencingActor @Inject() (
  assigner: LibrarySequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
