package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton, Provider }
import com.keepit.commanders._
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.slick.jdbc.{ PositionedResult, GetResult, StaticQuery }

@ImplementedBy(classOf[LibraryRepoImpl])
trait LibraryRepo extends Repo[Library] with SeqNumberFunction[Library] {
  def getByNameAndUserId(userId: Id[User], name: String, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getByUser(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE), excludeAccess: Option[LibraryAccess] = None)(implicit session: RSession): Seq[(LibraryMembership, Library)]
  def getByUserWithCollab(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE), excludeAccess: Option[LibraryAccess] = None)(implicit session: RSession): Seq[(LibraryMembership, Library, Boolean)]
  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): List[Library]
  def getAllByOwners(ownerIds: Set[Id[User]], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): List[Library]
  def getBySlugAndUserId(userId: Id[User], slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getByNameOrSlug(userId: Id[User], name: String, slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getOpt(ownerId: Id[User], slug: LibrarySlug)(implicit session: RSession): Option[Library]
  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession): Unit
  def getLibraries(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library]
  def hasKindsByOwner(ownerId: Id[User], kinds: Set[LibraryKind], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Boolean
  def countWithState(state: State[Library])(implicit session: RSession): Int

  //
  // Profile Library Repo functions
  //
  def countOwnerLibrariesForAnonymous(userId: Id[User])(implicit session: RSession): Int
  def countOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User])(implicit session: RSession): Int
  // def countFollowingLibrariesForSelf(userId: Id[User])(implicit session: RSession): Int  // use LibraryMembershipRepo.countWithUserIdAndAccess(userId, READ_ONLY) instead (cached)

  def getOwnerLibrariesForAnonymous(ownerId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getOwnerLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getInvitedLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[(Library, LibraryInvite)]

  def getFollowingLibrariesForAnonymous(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]

  def countLibrariesForOtherUserByAccess(userId: Id[User], viewerId: Id[User])(implicit session: RSession): Map[LibraryAccess, Int]
  def countLibrariesForAnonymousByAccess(userId: Id[User])(implicit session: RSession): Map[LibraryAccess, Int]

  def countOwnerLibrariesUserFollows(ownerId: Id[User], userId: Id[User])(implicit session: RSession): Int
  def getOwnerLibrariesUserFollows(owner: Id[User], other: Id[User])(implicit session: RSession): Seq[Library]

  def countMutualLibrariesForUsers(user1: Id[User], users: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def getMutualLibrariesForUser(user1: Id[User], user2: Id[User], offset: Int, size: Int)(implicit session: RSession): Seq[Library]

  // other
  def getOwnerLibraryCounts(owners: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def getAllPublishedNonEmptyLibraries(minKeepCount: Int)(implicit session: RSession): Seq[Id[Library]]
  def getNewPublishedLibraries(size: Int = 20)(implicit session: RSession): Seq[Library]
  def pagePublished(page: Paginator)(implicit session: RSession): Seq[Library]
  def countPublished(implicit session: RSession): Int
  def filterPublishedByMemberCount(minCount: Int, limit: Int = 100)(implicit session: RSession): Seq[Library]
}

@Singleton
class LibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryInviteRepo: Provider[LibraryInviteRepoImpl],
  val libraryMembershipRepo: Provider[LibraryMembershipRepoImpl],
  val libraryMetadataCache: LibraryMetadataCache,
  val topLibsCache: TopFollowedLibrariesCache,
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
    def color = column[LibraryColor]("color", O.Nullable)
    def kind = column[LibraryKind]("kind", O.NotNull)
    def universalLink = column[String]("universal_link", O.NotNull)
    def memberCount = column[Int]("member_count", O.NotNull)
    def lastKept = column[Option[DateTime]]("last_kept", O.Nullable)
    def keepCount = column[Int]("keep_count", O.NotNull)
    def whoCanInvite = column[Option[LibraryInvitePermissions]]("invite_collab", O.Nullable)
    def orgId = column[Option[Id[Organization]]]("organization_id", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, name, ownerId, description, visibility, slug, color.?, seq, kind, memberCount, universalLink, lastKept, keepCount, whoCanInvite, orgId) <> ((Library.applyFromDbRow _).tupled, Library.unapplyToDbRow _)
  }

  implicit val getLibraryResult: GetResult[Library] = GetResult { r: PositionedResult =>
    Library.applyFromDbRow(
      id = r.<<[Option[Id[Library]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      state = r.<<[State[Library]],
      name = r.<<[String],
      ownerId = r.<<[Id[User]],
      description = r.<<[Option[String]],
      visibility = r.<<[LibraryVisibility],
      slug = LibrarySlug(r.<<[String]),
      color = r.<<[Option[String]].map(LibraryColor(_)),
      seq = r.<<[SequenceNumber[Library]],
      kind = LibraryKind(r.<<[String]),
      memberCount = r.<<[Int],
      universalLink = r.<<[String],
      lastKept = r.<<[Option[DateTime]],
      keepCount = r.<<[Int],
      whoCanInvite = r.<<[Option[String]].map(LibraryInvitePermissions(_)),
      organizationId = r.<<[Option[Id[Organization]]]
    )
  }

  implicit val getLibraryAndLibraryInviteResult: GetResult[(Library, LibraryInvite)] = GetResult { r: PositionedResult =>
    (getLibraryResult(r), libraryInviteRepo.get.getLibraryInviteResult(r))
  }

  def table(tag: Tag) = new LibraryTable(tag)
  initTable()

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
    if (library.memberCount >= 5 + 1) {
      topLibsCache.remove(new TopFollowedLibrariesKey()) // lib with fewer than 5 followers will never enter that cache. This threshold need to by synced with RelatedLibraryCommanderImpl
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

  // Bad naming, but includes whether the library is a collaborative library
  def getByUserWithCollab(userId: Id[User], excludeState: Option[State[Library]], excludeAccess: Option[LibraryAccess])(implicit session: RSession): Seq[(LibraryMembership, Library, Boolean)] = {
    val libMemRows = libraryMembershipRepo.get.rows
    val readOnly: LibraryAccess = LibraryAccess.READ_ONLY
    val owner: LibraryAccess = LibraryAccess.OWNER
    val q = for {
      lib <- rows if lib.state =!= excludeState.orNull
      lm <- libMemRows if lm.libraryId === lib.id && lm.userId === userId && lm.access =!= excludeAccess.orNull && lm.state === LibraryMembershipStates.ACTIVE
    } yield (lm, lib, libMemRows.filter(r => r.libraryId === lib.id && r.access =!= readOnly && r.access =!= owner && r.userId === userId && r.state === LibraryMembershipStates.ACTIVE).exists)
    q.list
  }

  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): List[Library] = {
    (for { t <- rows if t.ownerId === ownerId && t.state =!= excludeState.orNull } yield t).list
  }

  def getAllByOwners(ownerIds: Set[Id[User]], excludeState: Option[State[Library]])(implicit session: RSession): List[Library] = {
    (for { t <- rows if t.ownerId.inSet(ownerIds) && t.state =!= excludeState.orNull } yield t).list
  }

  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession) = {
    val updateTime = Some(clock.now)
    (for { t <- rows if t.id === libraryId } yield (t.lastKept)).update(updateTime)
    invalidateCache(get(libraryId).copy(lastKept = updateTime))
  }

  def hasKindsByOwner(ownerId: Id[User], kinds: Set[LibraryKind], excludeState: Option[State[Library]])(implicit session: RSession): Boolean = {
    (for { t <- rows if t.ownerId === ownerId && t.kind.inSet(kinds) && t.state =!= excludeState.orNull } yield t).firstOption.isDefined
  }

  private val getOptCompiled = Compiled { (ownerId: Column[Id[User]], slug: Column[LibrarySlug]) =>
    (for (r <- rows if r.ownerId === ownerId && r.slug === slug) yield r)
  }
  def getOpt(ownerId: Id[User], slug: LibrarySlug)(implicit session: RSession): Option[Library] = {
    getOptCompiled(ownerId, slug).firstOption
  }

  def countWithState(state: State[Library])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select count(*) from library where state = 'active'"
    query.as[Int].firstOption.getOrElse(0)
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

  //
  // Profile Library Repo functions
  //
  // For Anonymous:   only libraries that are published and listed and non-empty (> 1 keep)
  // For Other User:  libraries that are published, listed, non-empty OR private, but user has membership to
  // For Self:        all libraries

  def countOwnerLibrariesForAnonymous(userId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and (lm.access='owner' or lm.access='read_write') and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published' and lib.keep_count > 0"
    query.as[Int].firstOption.getOrElse(0)
  }

  def countOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and (lm.access='owner' or lm.access='read_write') and lib.state = 'active' and lm.state = 'active' and ((lib.keep_count > 0 and lm.listed and lib.visibility = 'published') or (lib.id in (select lm.library_id from library_membership lm where lm.user_id = $friendId and lm.state = 'active')))"
    query.as[Int].firstOption.getOrElse(0)
  }

  def getOwnerLibrariesForAnonymous(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and (lm.access='owner' or lm.access='read_write') and lib.state = 'active' and lm.state = 'active' and (lib.keep_count > 0 and lm.listed and lib.visibility = 'published') order by lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and (lm.access='owner' or lm.access='read_write') and lib.state = 'active' and lm.state = 'active' and ((lib.keep_count > 0 and lm.listed and lib.visibility = 'published') or (lib.id in (select lm.library_id from library_membership lm where lm.user_id = $friendId and lm.state = 'active'))) order by lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getOwnerLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lib.* from library lib inner join library_membership lm on lm.user_id = $userId and lib.id = lm.library_id and lib.state='active' and lm.state='active' and (lm.access='owner' or lm.access='read_write') order by case lib.kind when 'system_main' then 1 when 'system_secret' then 2 else 3 end, lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getInvitedLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[(Library, LibraryInvite)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    // The technique we use below to ensure we get the desired invite for each library (maximum access level) is from:
    // http://stackoverflow.com/questions/12102200/get-records-with-max-value-for-each-group-of-grouped-sql-results#answer-28090544
    // It works right now because 'read_write' > 'read_only'. We'll need to modify the query (e.g. use a case expression) if we start
    // to allow sending invitations with additional access levels where lexicographical ordering does not match ordinal ordering.
    sql"""
      select lib.*, li.id, li.library_id, li.inviter_id, li.user_id, li.email_address, li.access, li.created_at, li.updated_at, li.state, li.auth_token, li.message
      from library lib,
        (select v1.* from library_invite v1 left join library_invite v2 on v2.user_id = v1.user_id and v2.library_id = v1.library_id and v2.state = v1.state and (v1.access < v2.access or (v1.access = v2.access and v1.id < v2.id)) where v1.user_id=$userId and v1.state='active' and v2.id is null) li
      where lib.id = li.library_id and lib.state='active'
      order by lib.member_count desc, lib.last_kept desc, lib.id desc
      limit ${page.itemsToDrop}, ${page.size}
    """.as[(Library, LibraryInvite)].list
  }

  def getFollowingLibrariesForAnonymous(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published' and lm.access != 'owner' order by lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getFollowingLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.access != 'owner' and ((lm.listed and lib.visibility = 'published' and lm.access != 'owner') #$libVisibility) order by lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getFollowingLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lib.* from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.access != 'owner' order by lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  // TODO: share query logic with getFollowingLibrariesForOtherUser (above)
  def countLibrariesForOtherUserByAccess(userId: Id[User], viewerId: Id[User])(implicit session: RSession): Map[LibraryAccess, Int] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $viewerId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = sql"select lm.access, count(lib.id) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and ((lm.listed and lib.visibility = 'published') #$libVisibility) and lib.keep_count > 0 group by lm.access"
    query.as[(String, Int)].list.toMap.map { case (a, c) => LibraryAccess(a) -> c }
  }

  // TODO: share query logic with getFollowingLibrariesForAnonymous (above)
  def countLibrariesForAnonymousByAccess(userId: Id[User])(implicit session: RSession): Map[LibraryAccess, Int] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lm.access, count(lib.id) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published' and lib.keep_count > 0 group by lm.access"
    query.as[(String, Int)].list.toMap.map { case (a, c) => LibraryAccess(a) -> c }
  }

  def countOwnerLibrariesUserFollows(ownerId: Id[User], userId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active'".as[Int].firstOption.getOrElse(0)
  }

  def getOwnerLibrariesUserFollows(owner: Id[User], other: Id[User])(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"""select * from library lib where lib.id in (select lm1.library_id from library_membership lm1 inner join library_membership lm2 on lm1.library_id = lm2.library_id where lm1.user_id = $owner and lm1.access = 'owner' and lm1.state = 'active' and lm2.user_id = $other and lm2.access != 'owner' and lm2.state = 'active') order by member_count desc, last_kept desc"""
    query.as[Library].list
  }

  def countMutualLibrariesForUsers(user1: Id[User], users: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (users.size > 0) {
      val userIdSet = users.mkString(",")
      val query = sql"""select lm2.user_id, count(*) from library_membership lm1 inner join library_membership lm2 on lm1.library_id = lm2.library_id where lm1.user_id = $user1 and lm1.access != 'owner' and lm1.state = 'active' and (lm2.user_id in (#$userIdSet)) and lm2.access != 'owner' and lm2.state = 'active' group by lm2.user_id"""
      query.as[(Id[User], Int)].list.toMap
    } else {
      Map.empty[Id[User], Int]
    }
  }

  def getMutualLibrariesForUser(user1: Id[User], user2: Id[User], offset: Int, size: Int)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"""select * from library lib where lib.id in (select lm1.library_id from library_membership lm1 inner join library_membership lm2 on lm1.library_id = lm2.library_id where lm1.user_id = $user1 and lm1.access != 'owner' and lm1.state = 'active' and lm2.user_id = $user2 and lm2.access != 'owner' and lm2.state = 'active') order by member_count desc, last_kept desc limit $size offset $offset"""
    query.as[Library].list
  }

  def getAllPublishedNonEmptyLibraries(minKeepCount: Int)(implicit session: RSession): Seq[Id[Library]] = {
    (for (r <- rows if r.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && r.state === LibraryStates.ACTIVE && r.keepCount >= minKeepCount) yield r.id).list
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

  def getOwnerLibraryCounts(owners: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int] = {
    import StaticQuery.interpolation
    if (owners.isEmpty) {
      Map()
    } else {
      val inIds = owners.map { _.id }.mkString("(", ",", ")")
      val q = sql"""select owner_id, count(*) from library  where owner_id in #${inIds} and kind = 'user_created' and state = 'active' and visibility = 'published' group by owner_id"""

      val cnts = q.as[(Int, Int)].list.map { case (userId, count) => Id[User](userId) -> count }.toMap
      owners.map { user => user -> cnts.getOrElse(user, 0) }.toMap
    }
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
