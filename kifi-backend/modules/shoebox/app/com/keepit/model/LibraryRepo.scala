package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton, Provider }
import com.keepit.commanders._
import com.keepit.commanders.gen.{ BasicLibraryByIdKey, BasicLibraryByIdCache }
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.EnumFormat
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.typeahead.{ LibraryResultTypeaheadCache, LibraryResultTypeaheadKey }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import scala.concurrent.duration._
import scala.slick.jdbc.{ PositionedResult, GetResult, StaticQuery }

@ImplementedBy(classOf[LibraryRepoImpl])
trait LibraryRepo extends Repo[Library] with SeqNumberFunction[Library] {
  def getActiveByIds(ids: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library]
  def getByUser(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE), excludeAccess: Option[LibraryAccess] = None)(implicit session: RSession): Seq[(LibraryMembership, Library)]
  def getLibrariesWithWriteAccess(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Seq[(Library, LibraryMembership)]
  def getLibrariesWithOpenWriteAccess(organizationId: Id[Organization], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Seq[Library]
  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): List[Library]
  def getBySpace(space: LibrarySpace, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Set[Library]
  def pageBySpace(viewer: Id[User], space: LibrarySpace, offset: Int, limit: Int)(implicit session: RSession): Seq[Library]
  def getBySpaceAndSlug(space: LibrarySpace, slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getBySpaceAndKind(space: LibrarySpace, kind: LibraryKind, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Set[Library]
  def getBySpaceAndKinds(space: LibrarySpace, kinds: Set[LibraryKind], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Set[Library]
  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession): Unit

  // PSA: Please use this function going forward for uncached queries, it's nonsense to have a million single-purpose queries
  def getLibraryIdsForQuery(query: LibraryQuery, extraInfo: LibraryQuery.ExtraInfo)(implicit session: RSession): Seq[Id[Library]]
  //
  // Profile Library Repo functions
  //
  def countOwnerLibrariesForAnonymous(userId: Id[User])(implicit session: RSession): Int
  def countOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User])(implicit session: RSession): Int
  // def countFollowingLibrariesForSelf(userId: Id[User])(implicit session: RSession): Int  // use LibraryMembershipRepo.countWithUserIdAndAccess(userId, READ_ONLY) instead (cached)

  def getOwnerLibrariesForAnonymous(ownerId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library]
  def getOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library]
  def getOwnerLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library]
  def getOwnerLibrariesForSelfWithOrdering(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library]

  def getFollowingLibrariesForAnonymous(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library]
  def getFollowingLibrariesForSelf(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library]

  def countLibrariesForOtherUserByAccess(userId: Id[User], viewerId: Id[User])(implicit session: RSession): Map[LibraryAccess, Int]
  def countLibrariesForAnonymousByAccess(userId: Id[User])(implicit session: RSession): Map[LibraryAccess, Int]

  def countOwnerLibrariesUserFollows(ownerId: Id[User], userId: Id[User])(implicit session: RSession): Int
  def getOwnerLibrariesUserFollows(owner: Id[User], other: Id[User])(implicit session: RSession): Seq[Library]

  def countMutualLibrariesForUsers(user1: Id[User], users: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def getMutualLibrariesForUser(user1: Id[User], user2: Id[User], offset: Int, size: Int)(implicit session: RSession): Seq[Library]

  // Org Library methods
  def countVisibleOrganizationLibraries(orgId: Id[Organization], includeOrgVisibleLibraries: Boolean, viewerLibraryMemberships: Set[Id[Library]])(implicit session: RSession): Int
  def countOrganizationLibraries(orgId: Id[Organization])(implicit session: RSession): Int
  def countSlackOrganizationLibraries(orgId: Id[Organization])(implicit session: RSession): Int
  def getVisibleOrganizationLibraries(orgId: Id[Organization], includeOrgVisibleLibraries: Boolean, viewerLibraryMemberships: Set[Id[Library]], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Library]
  def getOrganizationLibraries(orgId: Id[Organization])(implicit session: RSession): Seq[Library]

  // other
  def getOwnerLibraryCounts(owners: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def getAllPublishedNonEmptyLibraries(minKeepCount: Int)(implicit session: RSession): Seq[Id[Library]]
  def getNewPublishedLibraries(size: Int = 20)(implicit session: RSession): Seq[Library]
  def pagePublished(page: Paginator)(implicit session: RSession): Seq[Library]
  def countPublished(implicit session: RSession): Int
  def filterPublishedByMemberCount(minCount: Int, limit: Int = 100)(implicit session: RSession): Seq[Library]

  def deactivate(model: Library)(implicit session: RWSession): Unit
  def countPublishedNonEmptyOrgLibraries(orgId: Id[Organization], minKeepCount: Int = 2)(implicit session: RSession): Int
}

@Singleton
class LibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryInviteRepo: Provider[LibraryInviteRepoImpl],
  val libraryMembershipRepo: Provider[LibraryMembershipRepoImpl],
  val orgMembershipRepo: Provider[OrganizationMembershipRepoImpl],
  val libraryMetadataCache: LibraryMetadataCache,
  val basicLibraryCache: BasicLibraryByIdCache,
  val topLibsCache: TopFollowedLibrariesCache,
  relevantSuggestedLibrariesCache: RelevantSuggestedLibrariesCache,
  libraryResultTypeaheadCache: LibraryResultTypeaheadCache,
  val idCache: LibraryIdCache)
    extends DbRepo[Library] with LibraryRepo with SeqNumberDbFunction[Library] with Logging {

  lazy val lmRows = libraryMembershipRepo.get.activeRows
  lazy val omRows = orgMembershipRepo.get.activeRows

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
    def orgMemberAccess = column[Option[LibraryAccess]]("organization_member_access", O.Nullable)
    def whoCanComment = column[LibraryCommentPermissions]("who_can_comment", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, name, ownerId, description, visibility, slug, color.?, seq, kind, memberCount, universalLink, lastKept, keepCount, whoCanInvite, orgId, orgMemberAccess, whoCanComment) <> ((Library.applyFromDbRow _).tupled, Library.unapplyToDbRow _)
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
      organizationId = r.<<[Option[Id[Organization]]],
      organizationMemberAccess = r.<<[Option[LibraryAccess]],
      whoCanComment = r.<<[LibraryCommentPermissions]
    )
  }

  implicit val getLibraryAndLibraryInviteResult: GetResult[(Library, LibraryInvite)] = GetResult { r: PositionedResult =>
    (getLibraryResult(r), libraryInviteRepo.get.getLibraryInviteResult(r))
  }

  def table(tag: Tag) = new LibraryTable(tag)
  initTable()

  private def activeRows = rows.filter(_.state === LibraryStates.ACTIVE)

  override def save(library: Library)(implicit session: RWSession): Library = {
    val toSave = library.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def get(id: Id[Library])(implicit session: RSession): Library = {
    idCache.getOrElse(LibraryIdKey(id)) {
      getCompiled(id).first
    }
  }

  def getActiveByIds(ids: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library] = {
    idCache.bulkGetOrElse(ids.map(LibraryIdKey)) { missingKeys =>
      val q = rows.filter(lib => lib.id.inSet(missingKeys.map(_.id)) && lib.state === LibraryStates.ACTIVE)
      q.list.map { x => LibraryIdKey(x.id.get) -> x }.toMap
    }.collect { case (key, lib) if lib.isActive => key.id -> lib }
  }

  override def deleteCache(library: Library)(implicit session: RSession): Unit = {
    relevantSuggestedLibrariesCache.remove(RelevantSuggestedLibrariesKey(library.ownerId))
    library.id.map { id =>
      libraryResultTypeaheadCache.remove(LibraryResultTypeaheadKey(library.ownerId, id))
      libraryMetadataCache.remove(LibraryMetadataKey(id))
      basicLibraryCache.remove(BasicLibraryByIdKey(id))
      idCache.remove(LibraryIdKey(id))
    }
    if (library.memberCount >= 5 + 1) {
      topLibsCache.remove(new TopFollowedLibrariesKey()) // lib with fewer than 5 followers will never enter that cache. This threshold need to by synced with RelatedLibraryCommanderImpl
    }
  }

  override def invalidateCache(library: Library)(implicit session: RSession): Unit = {
    relevantSuggestedLibrariesCache.remove(RelevantSuggestedLibrariesKey(library.ownerId))
    library.id.map { id =>
      libraryResultTypeaheadCache.remove(LibraryResultTypeaheadKey(library.ownerId, id))
      libraryMetadataCache.remove(LibraryMetadataKey(id))
      basicLibraryCache.remove(BasicLibraryByIdKey(id))
      if (library.state == LibraryStates.INACTIVE) {
        deleteCache(library)
      } else {
        idCache.set(LibraryIdKey(id), library)
      }
    }
  }

  private def getByUserId(userId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): Set[Library] = {
    (for (b <- rows if b.ownerId === userId && b.orgId.isEmpty && b.state =!= excludeState.orNull) yield b).list.toSet
  }
  private def getByOrgId(orgId: Id[Organization], excludeState: Option[State[Library]])(implicit session: RSession): Set[Library] = {
    (for (b <- rows if b.orgId === orgId && b.state =!= excludeState.orNull) yield b).list.toSet
  }
  def getBySpace(space: LibrarySpace, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Set[Library] = {
    space match {
      case UserSpace(userId) => getByUserId(userId, excludeState)
      case OrganizationSpace(orgId) => getByOrgId(orgId, excludeState)
    }
  }
  def pageBySpace(viewer: Id[User], space: LibrarySpace, offset: Int, limit: Int)(implicit session: RSession): Seq[Library] = {
    def isViewerInOrg(orgId: Id[Organization]): Boolean = omRows.filter(om => om.userId === viewer && om.organizationId === orgId).exists.run
    val librariesInSpace = space match {
      case UserSpace(userId) => activeRows.filter(l => l.ownerId === userId && l.orgId.isEmpty)
      case OrganizationSpace(orgId) => activeRows.filter(l => l.orgId === orgId)
    }
    val librariesViewerCanSee = space match {
      case UserSpace(userId) if userId == viewer =>
        librariesInSpace
      case OrganizationSpace(orgId) if isViewerInOrg(orgId) =>
        val inOrgVisible: Set[LibraryVisibility] = Set(LibraryVisibility.PUBLISHED, LibraryVisibility.ORGANIZATION)
        librariesInSpace.filter(l => l.visibility.inSet(inOrgVisible) || lmRows.filter(lm => lm.libraryId === l.id && lm.userId === viewer).exists)
      case _ =>
        librariesInSpace.filter(l => lmRows.filter(lm => lm.libraryId === l.id && lm.userId === viewer).exists)
    }
    librariesViewerCanSee.sortBy(_.id).drop(offset).take(limit).list
  }

  def getLibraryIdsForQuery(query: LibraryQuery, extraInfo: LibraryQuery.ExtraInfo)(implicit session: RSession): Seq[Id[Library]] = {
    import LibraryQuery._
    val published: LibraryVisibility = LibraryVisibility.PUBLISHED
    val orgVisible: LibraryVisibility = LibraryVisibility.ORGANIZATION
    val arrangement = query.arrangement.getOrElse(Arrangement.GLOBAL_DEFAULT) // if we're going to do this, why not just set the default in the constructor?

    activeRows |> { rs =>
      // Actually perform the query
      query.target match {
        case ForOrg(orgId) => rs.filter(_.orgId === orgId)
        case ForUser(userId, roles) =>
          val libMemRows = libraryMembershipRepo.get.rows
          for {
            lib <- rs
            lm <- libMemRows
            if lib.ownerId === userId &&
              lib.orgId.isEmpty &&
              lm.libraryId === lib.id &&
              lm.userId === userId &&
              lm.access.inSet(roles) &&
              (lm.listed || lib.id.inSet(extraInfo.explicitlyAllowedLibraries))
          } yield lib
      }
    } |> { rs => // Now drop libraries that the viewer doesn't have access to
      rs.filter { lib =>
        lib.visibility === published ||
          (lib.visibility === orgVisible && lib.orgId.inSet(extraInfo.orgsWithVisibleLibraries)) ||
          lib.id.inSet(extraInfo.explicitlyAllowedLibraries)
      }
    } |> { rs => // then prepare to sort the libraries
      query.fromId.map { fromId =>
        val fromLib = get(fromId)
        arrangement.ordering match {
          case LibraryOrdering.MOST_RECENT_KEEPS_BY_USER | // use KeepToLibraryRepo.getSortedByKeepCountSince for this
            LibraryOrdering.ALPHABETICAL =>
            val fromName = fromLib.name
            arrangement.direction match {
              case SortDirection.ASCENDING => rs.filter(_.name > fromName)
              case SortDirection.DESCENDING => rs.filter(_.name < fromName)
            }
          case LibraryOrdering.LAST_KEPT_INTO =>
            val fromLastKept = fromLib.lastKept
            arrangement.direction match {
              case SortDirection.ASCENDING => rs.filter(_.lastKept > fromLastKept)
              case SortDirection.DESCENDING => rs.filter(_.lastKept < fromLastKept)
            }
          case LibraryOrdering.MEMBER_COUNT =>
            val fromMemberCount = fromLib.memberCount
            arrangement.direction match {
              case SortDirection.ASCENDING => rs.filter(lib => lib.memberCount > fromMemberCount || (lib.memberCount === fromMemberCount && lib.id > fromId))
              case SortDirection.DESCENDING => rs.filter(lib => lib.memberCount < fromMemberCount || (lib.memberCount === fromMemberCount && lib.id < fromId))
            }
        }
      }.getOrElse(rs)
    } |> { rs => // actually sort them
      arrangement match {
        case Arrangement(LibraryOrdering.ALPHABETICAL, SortDirection.ASCENDING) => rs.sortBy(lib => lib.name asc)
        case Arrangement(LibraryOrdering.ALPHABETICAL, SortDirection.DESCENDING) => rs.sortBy(lib => lib.name desc)
        case Arrangement(LibraryOrdering.LAST_KEPT_INTO, SortDirection.ASCENDING) => rs.sortBy(lib => lib.lastKept asc)
        case Arrangement(LibraryOrdering.LAST_KEPT_INTO, SortDirection.DESCENDING) => rs.sortBy(lib => lib.lastKept desc)
        case Arrangement(LibraryOrdering.MEMBER_COUNT, SortDirection.ASCENDING) => rs.sortBy(lib => (lib.memberCount asc, lib.id asc))
        case Arrangement(LibraryOrdering.MEMBER_COUNT, SortDirection.DESCENDING) => rs.sortBy(lib => (lib.memberCount desc, lib.id desc))
      }
    } |> { rs => // then page through the results
      rs.map(_.id).drop(query.offset.value).take(query.limit.value).list
    }
  }

  private def getByUserIdAndSlug(userId: Id[User], slug: LibrarySlug, excludeState: Option[State[Library]])(implicit session: RSession): Option[Library] = {
    (for (b <- rows if b.slug === slug && b.ownerId === userId && b.orgId.isEmpty && b.state =!= excludeState.orNull) yield b).firstOption
  }
  private def getByOrgIdAndSlug(orgId: Id[Organization], slug: LibrarySlug, excludeState: Option[State[Library]])(implicit session: RSession): Option[Library] = {
    (for (b <- rows if b.slug === slug && b.orgId === orgId && b.state =!= excludeState.orNull) yield b).firstOption
  }
  def getBySpaceAndSlug(space: LibrarySpace, slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library] = {
    space match {
      case UserSpace(userId) => getByUserIdAndSlug(userId, slug, excludeState)
      case OrganizationSpace(orgId) => getByOrgIdAndSlug(orgId, slug, excludeState)
    }
  }

  private def getByUserIdAndKinds(userId: Id[User], kinds: Set[LibraryKind], excludeState: Option[State[Library]])(implicit session: RSession) = {
    for (b <- rows if b.kind.inSet(kinds) && b.ownerId === userId && b.orgId.isEmpty && b.state =!= excludeState.orNull) yield b
  }
  private def getByOrgIdAndKinds(orgId: Id[Organization], kinds: Set[LibraryKind], excludeState: Option[State[Library]])(implicit session: RSession) = {
    for (b <- rows if b.kind.inSet(kinds) && b.orgId === orgId && b.state =!= excludeState.orNull) yield b
  }
  def getBySpaceAndKinds(space: LibrarySpace, kinds: Set[LibraryKind], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Set[Library] = {
    val q = space match {
      case UserSpace(userId) => getByUserIdAndKinds(userId, kinds, excludeState)
      case OrganizationSpace(orgId) => getByOrgIdAndKinds(orgId, kinds, excludeState)
    }
    q.list.toSet
  }

  def getBySpaceAndKind(space: LibrarySpace, kind: LibraryKind, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Set[Library] = getBySpaceAndKinds(space, Set(kind), excludeState)

  def getByUser(userId: Id[User], excludeState: Option[State[Library]], excludeAccess: Option[LibraryAccess])(implicit session: RSession): Seq[(LibraryMembership, Library)] = {
    val q = for {
      lib <- rows if lib.state =!= excludeState.orNull
      lm <- libraryMembershipRepo.get.rows if lm.libraryId === lib.id && lm.userId === userId && lm.access =!= excludeAccess.orNull && lm.state === LibraryMembershipStates.ACTIVE
    } yield (lm, lib)
    q.list
  }

  def getLibrariesWithWriteAccess(userId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): Seq[(Library, LibraryMembership)] = {
    val libMemRows = libraryMembershipRepo.get.rows
    val readOnly: LibraryAccess = LibraryAccess.READ_ONLY
    val owner: LibraryAccess = LibraryAccess.OWNER
    val q = for {
      lib <- rows if lib.state =!= excludeState.orNull
      lm <- libMemRows if lm.libraryId === lib.id && lm.userId === userId && lm.access =!= readOnly && lm.state === LibraryMembershipStates.ACTIVE
    } yield (lib, lm)
    q.list
  }

  def getLibrariesWithOpenWriteAccess(organizationId: Id[Organization], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Seq[Library] = {
    val collaborativePermissions = LibraryAccess.collaborativePermissions
    val whyCantScalaTypeInfer: LibraryVisibility = LibraryVisibility.SECRET
    // Treat null orgMemberAccess as READ_WRITE. Revisit if this is no longer true in the future.
    val q = for { r <- rows if r.orgId === organizationId && r.state =!= excludeState.orNull && r.visibility =!= whyCantScalaTypeInfer && (r.orgMemberAccess.inSet(collaborativePermissions) || r.orgMemberAccess.isEmpty) } yield r
    q.list
  }

  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): List[Library] = {
    (for { t <- rows if t.ownerId === ownerId && t.state =!= excludeState.orNull } yield t).list
  }

  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession) = {
    val updateTime = Some(clock.now)
    (for { t <- rows if t.id === libraryId } yield (t.lastKept)).update(updateTime)
    invalidateCache(get(libraryId).copy(lastKept = updateTime))
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

  def getOwnerLibrariesForAnonymous(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query =
      sql"""select lib.* from library_membership lm, library lib
           where lm.library_id = lib.id and lm.user_id = $userId
           and (lm.access='owner' or lm.access='read_write') and lib.organization_id is null
           and lib.state = 'active' and lm.state = 'active' and (lib.keep_count > 0
           and lm.listed and lib.visibility = 'published')
           order by #${getOrderBySql("lib", "lm", ordering, direction, orderedByPriority)} limit ${page.itemsToDrop}, ${page.size}"""
    query.as[Library].list
  }

  def getOwnerLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query =
      sql"""select lib.* from library_membership lm, library lib
           where lm.library_id = lib.id and lm.user_id = $userId
           and (lm.access='owner' or lm.access='read_write') and lib.organization_id is null and lib.state = 'active'
           and lm.state = 'active' and ((lib.keep_count > 0 and lm.listed
           and lib.visibility = 'published') or
           (lib.id in (select lm.library_id from library_membership lm where lm.user_id = $friendId and lm.state = 'active')))
           order by #${getOrderBySql("lib", "lm", ordering, direction, orderedByPriority)} limit ${page.itemsToDrop}, ${page.size}"""
    query.as[Library].list
  }

  def getOwnerLibrariesForSelf(userId: Id[User], page: Paginator)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select lib.* from library lib inner join library_membership lm on lm.user_id = $userId and lib.id = lm.library_id and lib.state='active' and lm.state='active' and (lm.access='owner' or lm.access='read_write') order by case lib.kind when 'system_main' then 1 when 'system_secret' then 2 else 3 end, lib.member_count desc, lib.last_kept desc, lib.id desc limit ${page.itemsToDrop}, ${page.size}"
    query.as[Library].list
  }

  def getOwnerLibrariesForSelfWithOrdering(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"""select lib.* from library lib
                  inner join library_membership lm on lm.user_id = $userId
                  and lib.id = lm.library_id and lib.state='active' and lm.state='active'
                  and (lm.access='owner' or lm.access='read_write') and lib.organization_id is null
                  order by case lib.kind when 'system_main' then 1 when 'system_secret' then 2 else 3 end,
                  #${getOrderBySql("lib", "lm", ordering, direction, orderedByPriority)} limit #${page.itemsToDrop}, #${page.size}"""
    query.as[Library].list
  }

  def getOrderBySql(tableName: String, membershipTable: String, orderingOpt: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true) = {
    val ordering = orderingOpt match {
      case Some(ordering) => ordering match {
        case LibraryOrdering.MOST_RECENT_KEEPS_BY_USER | // use KeepToLibrary.getSortedByKeepCountSince for this
          LibraryOrdering.ALPHABETICAL => s"$tableName.name ${direction.getOrElse(SortDirection.ASCENDING).value}, $tableName.id desc"
        case LibraryOrdering.MEMBER_COUNT => s"$tableName.member_count ${direction.getOrElse(SortDirection.DESCENDING).value}"
        case LibraryOrdering.LAST_KEPT_INTO => s"$tableName.last_kept ${direction.getOrElse(SortDirection.DESCENDING).value}"
      }
      case None => s"$tableName.member_count desc, $tableName.last_kept desc, $tableName.id desc"
    }
    val priorityOrdering = if (orderedByPriority) s"""$membershipTable.priority desc,""" else ""
    priorityOrdering + ordering
  }

  def getFollowingLibrariesForAnonymous(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query =
      sql"""select lib.* from library_membership lm, library lib
           where lm.library_id = lib.id and lm.user_id = $userId
           and lib.state = 'active' and lm.state = 'active'
           and lm.listed and lib.visibility = 'published' and lm.access = 'read_only'
           order by #${getOrderBySql("lib", "lm", ordering, direction, orderedByPriority)} limit ${page.itemsToDrop}, ${page.size}"""
    query.as[Library].list
  }

  def getFollowingLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query =
      sql"""select lib.* from library_membership lm, library lib
           where lm.library_id = lib.id and lm.user_id = $userId
           and lib.state = 'active' and lm.state = 'active' and lm.access = 'read_only'
           and ((lm.listed and lib.visibility = 'published' and lm.access = 'read_only') #$libVisibility)
           order by #${getOrderBySql("lib", "lm", ordering, direction, orderedByPriority)} limit ${page.itemsToDrop}, ${page.size}"""
    query.as[Library].list
  }

  def getFollowingLibrariesForSelf(userId: Id[User], page: Paginator, ordering: Option[LibraryOrdering], direction: Option[SortDirection] = None, orderedByPriority: Boolean = true)(implicit session: RSession): Seq[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query =
      sql"""select lib.* from library_membership lm, library lib
           where lm.library_id = lib.id and lm.user_id = $userId
           and lib.state = 'active' and lm.state = 'active' and lm.access = 'read_only'
           order by #${getOrderBySql("lib", "lm", ordering, direction, orderedByPriority)} limit ${page.itemsToDrop}, ${page.size}"""
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

  // Organization Library Repo methods
  private def visibleOrganizationLibrariesHelper(orgId: Id[Organization], includeOrgVisibleLibraries: Column[Boolean], viewerLibraryMemberships: Set[Id[Library]])(implicit session: RSession) = {
    val orgVisible: LibraryVisibility = LibraryVisibility.ORGANIZATION
    val published: LibraryVisibility = LibraryVisibility.PUBLISHED
    for {
      lib <- rows if lib.state === LibraryStates.ACTIVE && lib.orgId === orgId && (
        (includeOrgVisibleLibraries && (lib.visibility === orgVisible)) ||
        (lib.visibility === published) ||
        lib.id.inSet(viewerLibraryMemberships) // user's can see any library they are a member of
      )
    } yield lib
  }

  def countVisibleOrganizationLibraries(orgId: Id[Organization], includeOrgVisibleLibraries: Boolean, viewerLibraryMemberships: Set[Id[Library]])(implicit session: RSession): Int = {
    visibleOrganizationLibrariesHelper(orgId, includeOrgVisibleLibraries, viewerLibraryMemberships).length.run
  }

  def countOrganizationLibraries(orgId: Id[Organization])(implicit session: RSession): Int = {
    Query(
      (for { t <- rows if t.orgId === orgId && t.state === LibraryStates.ACTIVE } yield t).length
    ).first
  }

  def countSlackOrganizationLibraries(orgId: Id[Organization])(implicit session: RSession): Int = {
    val libKind: LibraryKind = LibraryKind.SLACK_CHANNEL
    Query(
      (for { t <- rows if t.orgId === orgId && t.state === LibraryStates.ACTIVE && t.kind === libKind } yield t).length
    ).first
  }

  def getVisibleOrganizationLibraries(orgId: Id[Organization], includeOrgVisibleLibraries: Boolean, viewerLibraryMemberships: Set[Id[Library]], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Library] = {
    visibleOrganizationLibrariesHelper(orgId, includeOrgVisibleLibraries, viewerLibraryMemberships).sortBy(_.lastKept desc).drop(offset.value).take(limit.value).list
  }

  def getOrganizationLibraries(orgId: Id[Organization])(implicit session: RSession): Seq[Library] = {
    (for (r <- rows if r.orgId === orgId) yield r).list
  }

  def getAllPublishedNonEmptyLibraries(minKeepCount: Int)(implicit session: RSession): Seq[Id[Library]] = {
    (for (r <- rows if r.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && r.state === LibraryStates.ACTIVE && r.keepCount >= minKeepCount) yield r.id).list
  }

  def getNewPublishedLibraries(size: Int = 20)(implicit session: RSession): Seq[Library] = {
    (for (r <- rows if r.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && r.state === LibraryStates.ACTIVE) yield r).sortBy(_.id.desc).take(size).list
  }

  def countPublishedNonEmptyOrgLibraries(orgId: Id[Organization], minKeepCount: Int = 2)(implicit session: RSession): Int = {
    (for (r <- rows if r.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) && r.state === LibraryStates.ACTIVE && r.orgId === orgId && r.keepCount >= minKeepCount) yield r.id).length.run
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
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (owners.isEmpty) {
      Map()
    } else {
      val inIds = owners.map { _.id }.mkString("(", ",", ")")
      val q = sql"""select owner_id, count(*) from library where owner_id in #${inIds} and kind = 'user_created' and state = 'active' and visibility = 'published' group by owner_id"""

      val cnts = q.as[(Int, Int)].list.map { case (userId, count) => Id[User](userId) -> count }.toMap
      owners.map { user => user -> cnts.getOrElse(user, 0) }.toMap
    }
  }

  def deactivate(model: Library)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}

trait LibrarySequencingPlugin extends SequencingPlugin

class LibrarySequencingPluginImpl @Inject() (
    override val actor: ActorInstance[LibrarySequencingActor],
    override val scheduling: SchedulingProperties) extends LibrarySequencingPlugin {

  override val interval: FiniteDuration = 20.seconds
}

/*
    Does as it it says, a class making it more typesafe to refer
    to a specific ordering of libraries, currently there exist 3:
    - alphabetical, last kept into, member count
 */
sealed abstract class LibraryOrdering(val value: String)

object LibraryOrdering extends Enumerator[LibraryOrdering] {
  case object LAST_KEPT_INTO extends LibraryOrdering("last_kept_into")
  case object ALPHABETICAL extends LibraryOrdering("alphabetical")
  case object MEMBER_COUNT extends LibraryOrdering("member_count")
  case object MOST_RECENT_KEEPS_BY_USER extends LibraryOrdering("most_recent_keeps_by_user")

  val all = _all
  def fromStr(str: String): Option[LibraryOrdering] = all.find(_.value == str)
  def apply(str: String): LibraryOrdering = fromStr(str).get

  implicit val format: Format[LibraryOrdering] = EnumFormat.format(fromStr, _.value)

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[LibraryOrdering] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, LibraryOrdering]] = {
      stringBinder.bind(key, params) map {
        case Right(str) => Right(LibraryOrdering(str))
        case _ => Left("Unable to bind a LibraryOrdering")
      }
    }
    override def unbind(key: String, ordering: LibraryOrdering): String = {
      stringBinder.unbind(key, ordering.value)
    }
  }
}

@Singleton
class LibrarySequenceNumberAssigner @Inject() (db: Database, repo: LibraryRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[Library](db, repo, airbrake)

class LibrarySequencingActor @Inject() (
  assigner: LibrarySequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
