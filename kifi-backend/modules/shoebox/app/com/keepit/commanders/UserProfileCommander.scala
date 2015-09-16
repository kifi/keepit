package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.common.util.Paginator
import com.keepit.common.performance.StatsdTiming
import com.keepit.graph.GraphServiceClient
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json.Json

import scala.collection.parallel.ParSeq
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration

case class ConnectedUserId(userId: Id[User], connected: Boolean)
object ConnectedUserId {
  implicit val formatter = Json.format[ConnectedUserId]
}

case class FollowerUserId(userId: Id[User], connected: Boolean)
object FollowerUserId {
  implicit val formatter = Json.format[FollowerUserId]
}

class UserProfileCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryImageRepo: LibraryImageRepo,
    basicUserRepo: BasicUserRepo,
    userConnectionRepo: UserConnectionRepo,
    userValueRepo: UserValueRepo,
    libraryInfoCommander: LibraryInfoCommander,
    graphServiceClient: GraphServiceClient,
    organizationCommander: OrganizationCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val config: PublicIdConfiguration) {

  def getOwnLibrariesForSelf(user: User, page: Paginator, idealSize: ImageSize): ParSeq[LibraryCardInfo] = {
    getOwnLibrariesForSelf(user, page, idealSize, None, None, false)
  }

  def getOwnLibrariesForSelf(user: User, page: Paginator, idealSize: ImageSize, ordering: Option[LibraryOrdering], direction: Option[SortDirection], orderedByPriority: Boolean): ParSeq[LibraryCardInfo] = {
    val (libraryInfos, memberships, permissionsFromOrg) = db.readOnlyMaster { implicit session =>
      val libs = libraryRepo.getOwnerLibrariesForSelfWithOrdering(user.id.get, page, ordering, direction, orderedByPriority)
      val libOwnerIds = libs.map(_.ownerId).toSet
      val owners = basicUserRepo.loadAll(libOwnerIds)
      val libraryIds = libs.map(_.id.get).toSet
      val memberships = libraryMembershipRepo.getWithLibraryIdsAndUserId(libraryIds, user.id.get)
      val libraryInfos = libraryInfoCommander.createLibraryCardInfos(libs, owners, Some(user), true, idealSize) zip libs
      val permissionsFromOrg = libs.map(lib => lib.id.get -> libraryInfoCommander.getLibraryPermissionsFromOrgPermissions(lib.organizationId, user.id)).toMap
      (libraryInfos, memberships, permissionsFromOrg)
    }
    libraryInfos map {
      case (info, lib) =>
        LibraryCardInfo( // why does this reconstruct the object? seems like libraryInfoCommander.createLibraryCardInfo fills out everything
          id = info.id,
          name = info.name,
          description = info.description,
          color = info.color,
          image = info.image,
          slug = info.slug,
          kind = lib.kind,
          visibility = lib.visibility,
          owner = info.owner,
          numKeeps = info.numKeeps,
          numFollowers = info.numFollowers,
          followers = info.followers,
          numCollaborators = info.numCollaborators,
          collaborators = info.collaborators,
          lastKept = lib.lastKept.getOrElse(lib.createdAt),
          following = Some(true),
          membership = memberships(lib.id.get).map(mem => lib.createMembershipInfo(mem, permissionsFromOrg(lib.id.get))),
          modifiedAt = lib.updatedAt,
          path = info.path,
          org = info.org,
          orgMemberAccess = info.orgMemberAccess
        )
    }
  }

  @StatsdTiming("UserProfileCommander.getOwnLibraries")
  def getOwnLibraries(user: User, viewer: Option[User], page: Paginator, idealSize: ImageSize, ordering: Option[LibraryOrdering] = None, direction: Option[SortDirection] = None, orderedByPriority: Boolean = false): ParSeq[LibraryCardInfo] = {
    db.readOnlyMaster { implicit session =>
      val libs = viewer match {
        case None =>
          libraryRepo.getOwnerLibrariesForAnonymous(user.id.get, page, ordering, direction, orderedByPriority)
        case Some(other) =>
          libraryRepo.getOwnerLibrariesForOtherUser(user.id.get, other.id.get, page, ordering, direction, orderedByPriority)
      }
      val libOwnerIds = libs.map(_.ownerId).toSet
      val owners = basicUserRepo.loadAll(libOwnerIds)
      libraryInfoCommander.createLibraryCardInfos(libs, owners, viewer, true, idealSize)
    }
  }

  @StatsdTiming("UserProfileCommander.getFollowedLibraries")
  def getFollowingLibraries(user: User, viewer: Option[User], page: Paginator, idealSize: ImageSize, ordering: Option[LibraryOrdering] = None, direction: Option[SortDirection] = None, orderedByPriority: Boolean = false): ParSeq[LibraryCardInfo] = {
    db.readOnlyMaster { implicit session =>
      val libs = viewer match {
        case None =>
          val showFollowLibraries = getUserValueSetting(user.id.get, UserValueName.SHOW_FOLLOWED_LIBRARIES)
          if (showFollowLibraries) {
            libraryRepo.getFollowingLibrariesForAnonymous(user.id.get, page, ordering, direction, orderedByPriority)
          } else Seq.empty
        case Some(other) if other.id == user.id =>
          libraryRepo.getFollowingLibrariesForSelf(user.id.get, page, ordering, direction, orderedByPriority)
        case Some(other) =>
          val showFollowLibraries = getUserValueSetting(user.id.get, UserValueName.SHOW_FOLLOWED_LIBRARIES)
          if (showFollowLibraries) {
            libraryRepo.getFollowingLibrariesForOtherUser(user.id.get, other.id.get, page, ordering, direction, orderedByPriority)
          } else Seq.empty
      }
      val owners = basicUserRepo.loadAll(libs.map(_.ownerId).toSet)
      libraryInfoCommander.createLibraryCardInfos(libs, owners, viewer, true, idealSize)
    }
  }

  def getInvitedLibraries(user: User, viewer: Option[User], page: Paginator, idealSize: ImageSize): ParSeq[LibraryCardInfo] = {
    if (viewer.exists(_.id == user.id)) {
      db.readOnlyMaster { implicit session =>
        val (libs, invites) = libraryRepo.getInvitedLibrariesForSelf(user.id.get, page).unzip
        val ownersAndInviters = basicUserRepo.loadAll((libs.map(_.ownerId) ++ invites.map(_.inviterId)).toSet)
        libraryInfoCommander.createLibraryCardInfos(libs, ownersAndInviters, viewer, false, idealSize) zip invites map {
          case (card, invite) =>
            card.copy(invite = Some(LibraryInviteInfo.createInfo(invite, ownersAndInviters(invite.inviterId))))
        }
      }
    } else {
      ParSeq.empty
    }
  }

  def getFollowersByViewer(userId: Id[User], viewer: Option[Id[User]]): Seq[Id[User]] = db.readOnlyMaster { implicit s =>
    viewer match {
      case None =>
        libraryMembershipRepo.getFollowersForAnonymous(userId)
      case Some(id) if id == userId =>
        libraryMembershipRepo.getFollowersForOwner(userId)
      case Some(viewerId) =>
        libraryMembershipRepo.getFollowersForOtherUser(userId, viewerId)
    }
  }

  def getOwnerLibraryCounts(users: Set[Id[User]]): Map[Id[User], Int] = {
    db.readOnlyReplica { implicit s =>

      libraryRepo.getOwnerLibraryCounts(users)
    }
  }

  // number of libraries user owns that the viewer can see
  // number of libraries user collaborates to that the viewer can see
  // number of libraries user follows that the viewer can see
  // number of libraries user is invited to (only if user is viewing his/her own profile)
  def countLibraries(userId: Id[User], viewer: Option[Id[User]]): (Int, Int, Int, Option[Int]) = {
    viewer match {
      case None =>
        db.readOnlyReplica { implicit s =>
          val counts = libraryRepo.countLibrariesForAnonymousByAccess(userId)
          val numLibsOwned = counts.getOrElse(LibraryAccess.OWNER, 0)
          val numLibsCollab = counts.getOrElse(LibraryAccess.READ_WRITE, 0)
          val numLibsFollowing = counts.getOrElse(LibraryAccess.READ_ONLY, 0)
          (numLibsOwned, numLibsCollab, numLibsFollowing, None)
        }
      case Some(id) if id == userId =>
        val (numLibsOwned, numLibsCollab, numLibsFollowing) = db.readOnlyMaster { implicit s =>
          val counts = libraryMembershipRepo.countsWithUserIdAndAccesses(userId, LibraryAccess.all.toSet)
          val numLibsOwned = counts.getOrElse(LibraryAccess.OWNER, 0)
          val numLibsCollab = counts.getOrElse(LibraryAccess.READ_WRITE, 0)
          val numLibsFollowing = counts.getOrElse(LibraryAccess.READ_ONLY, 0)
          (numLibsOwned, numLibsCollab, numLibsFollowing)
        }
        val numLibsInvited = db.readOnlyReplica { implicit s =>
          libraryInviteRepo.countDistinctWithUserId(userId)
        }
        (numLibsOwned, numLibsCollab, numLibsFollowing, Some(numLibsInvited))
      case Some(viewerId) =>
        db.readOnlyReplica { implicit s =>
          val counts = libraryRepo.countLibrariesForOtherUserByAccess(userId, viewerId)
          val numLibsOwned = counts.getOrElse(LibraryAccess.OWNER, 0)
          val numLibsCollab = counts.getOrElse(LibraryAccess.READ_WRITE, 0) + counts.getOrElse(LibraryAccess.READ_ONLY, 0)
          val numLibsFollowing = counts.getOrElse(LibraryAccess.READ_ONLY, 0)
          (numLibsOwned, numLibsCollab, numLibsFollowing, None)
        }
    }
  }

  def countFollowers(userId: Id[User], viewer: Option[Id[User]]): Int = db.readOnlyReplica { implicit s =>
    viewer match {
      case None =>
        libraryMembershipRepo.countFollowersForAnonymous(userId)
      case Some(id) if id == userId =>
        libraryMembershipRepo.countFollowersForOwner(userId)
      case Some(viewerId) =>
        libraryMembershipRepo.countFollowersForOtherUser(userId, viewerId)
    }
  }

  def getConnectionsSortedByRelationship(viewer: Id[User], owner: Id[User]): Future[Seq[ConnectedUserId]] = {
    val sociallyRelatedEntitiesF = graphServiceClient.getSociallyRelatedEntitiesForUser(viewer)
    val connectionsF = db.readOnlyMasterAsync { implicit s =>
      val all = userConnectionRepo.getConnectedUsersForUsers(Set(viewer, owner)) //cached
      (all.getOrElse(viewer, Set.empty), all.getOrElse(owner, Set.empty))
    }
    for {
      sociallyRelatedEntities <- sociallyRelatedEntitiesF
      (viewerConnections, ownerConnections) <- connectionsF
    } yield {
      val relatedUsersMap = sociallyRelatedEntities.map(_.users.related).getOrElse(Seq.empty).toMap
      val ownerConnectionsMap = collection.mutable.Map(ownerConnections.toSeq.zip(List.fill(ownerConnections.size)(0d)): _*)
      //if its a mutual connection, set the relationship score to 100
      viewerConnections.filter(ownerConnectionsMap.contains).foreach { con =>
        ownerConnectionsMap(con) = 100d
      }
      relatedUsersMap.filter(t => ownerConnectionsMap.contains(t._1)).foreach {
        case (con, score) =>
          val newScore = ownerConnectionsMap(con) + score
          ownerConnectionsMap(con) = newScore
      }
      if (ownerConnectionsMap.contains(viewer)) {
        ownerConnectionsMap(viewer) = 10000d
      }
      val scoring = ownerConnectionsMap.toSeq.sortBy(_._2 * -1)
      val res = scoring.map(t => ConnectedUserId(t._1, t._1 == viewer || viewerConnections.contains(t._1)))
      res
    }
  }

  def getFollowersSortedByRelationship(viewerOpt: Option[Id[User]], owner: Id[User]): Future[Seq[FollowerUserId]] = {
    val sociallyRelatedEntitiesF = graphServiceClient.getSociallyRelatedEntitiesForUser(viewerOpt.getOrElse(owner))
    val followersF = db.readOnlyReplicaAsync { implicit s =>
      getFollowersByViewer(owner, viewerOpt)
    }
    val connectionsF: Future[Set[Id[User]]] = viewerOpt.map { viewer =>
      db.readOnlyReplicaAsync { implicit s =>
        userConnectionRepo.getConnectedUsers(viewer)
      }
    }.getOrElse(Future.successful(Set.empty))
    for {
      sociallyRelatedEntities <- sociallyRelatedEntitiesF
      followers <- followersF
      viewerConnections <- connectionsF
    } yield {
      val relatedUsersMap = sociallyRelatedEntities.map(_.users.related).getOrElse(Seq.empty).toMap
      val followersMap = collection.mutable.Map(followers.toSeq.zip(List.fill(followers.size)(0d)): _*)
      //if its a mutual connection, set the relationship score to 100
      viewerConnections.filter(followers.contains).foreach { con =>
        followersMap(con) = 100d
      }
      relatedUsersMap.filter(t => followersMap.contains(t._1)).foreach {
        case (con, score) =>
          val newScore = followersMap(con) + score
          followersMap(con) = newScore
      }
      viewerOpt.foreach { viewer =>
        if (followersMap.contains(viewer)) {
          followersMap(viewer) = 10000d
        }
      }
      val scoring = followersMap.toSeq.sortBy(_._2 * -1)
      scoring.map(t => FollowerUserId(t._1, viewerOpt.exists(_ == t._1) || viewerConnections.contains(t._1)))
    }
  }

  private def getUserValueSetting(userId: Id[User], userVal: UserValueName)(implicit rs: RSession): Boolean = {
    val settingsJs = userValueRepo.getValue(userId, UserValues.userProfileSettings)
    UserValueSettings.retrieveSetting(userVal, settingsJs)
  }

}
