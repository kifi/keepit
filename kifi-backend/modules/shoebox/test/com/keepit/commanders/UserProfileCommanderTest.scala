package com.keepit.commanders

import com.keepit.abook.model.EmailAccountInfo
import com.keepit.abook.{ FakeABookServiceClientModule }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.ImageSize
import com.keepit.common.util.Paginator
import com.keepit.graph.model.{ RelatedEntities, SociallyRelatedEntities }
import com.keepit.graph.{ FakeGraphServiceClientImpl, FakeGraphServiceModule, GraphServiceClient }
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UserProfileCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeGraphServiceModule(),
    FakeSocialGraphModule()
  )

  private val profileLibraryOrdering = new Ordering[Library] {
    def compare(self: Library, that: Library): Int =
      (self.kind compare that.kind) match {
        case 0 =>
          (self.memberCount compare that.memberCount) match {
            case 0 => -(self.id.get.id compare that.id.get.id)
            case c => -c
          }
        case c => c
      }
  }

  "get ownerLibraries (self, anonymous, friend)" in {
    withDb(modules: _*) { implicit injector =>
      implicit val config = inject[PublicIdConfiguration]
      val commander = inject[UserProfileCommander]
      val libraryCommander = inject[LibraryCommander]

      val (owner, other, userCreatedLibs) = db.readWrite { implicit s =>
        val owner = user().saved
        val other = user().saved

        val libPublishedLots = library().withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withMemberCount(50).withSlug("published-lots").saved
        val libPublishedFew = library().withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withMemberCount(3).withSlug("published-few").saved
        val libPublishedFewUpdated = library().withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withMemberCount(3).withLastKept().withSlug("published-few-updated").saved
        val libPublishedEmpty = library().withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(0).withSlug("published-empty").saved

        // a library `owner` has, with another collaborator
        val libPublishedCollabWithMe = library().withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withMemberCount(2).withSlug("published-collab-with-me").saved
        membership().withLibraryCollaborator(libPublishedCollabWithMe, other).saved
        // a library owned by someone else, but `owner` collaborates with
        val libPublishedCollabWithOther = library().withUser(other).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withMemberCount(2).withSlug("published-collab-with-other").saved
        membership().withLibraryCollaborator(libPublishedCollabWithOther, owner).saved

        val libPublishedHidden = library().withUser(owner).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withSlug("published-hidden").saved
        libraryMembershipRepo.getWithLibraryIdAndUserId(libPublishedHidden.id.get, owner.id.get).map { mem =>
          libraryMembershipRepo.save(mem.copy(listed = false))
        }

        val libPrivate = library().withUser(owner).withVisibility(LibraryVisibility.SECRET).withKeepCount(3).withSlug("private").saved
        val libPrivateFollower = library().withUser(owner).withVisibility(LibraryVisibility.SECRET).withKeepCount(3).withMemberCount(2).withSlug("private-follower").saved
        membership().withLibraryFollower(libPrivateFollower, other).saved
        val libPrivateCollab = library().withUser(owner).withVisibility(LibraryVisibility.SECRET).withKeepCount(3).withMemberCount(2).withSlug("private-collab").saved
        membership().withLibraryCollaborator(libPrivateCollab, other).saved

        val wrongLib = library().withUser(other).withVisibility(LibraryVisibility.PUBLISHED).withKeepCount(3).withSlug("wrong-library").saved

        val ownerLibs = Seq(
          libPublishedLots, // published library (lots of followers) - test ordering
          libPublishedFew, // published library (not many followers) - test ordering
          libPublishedFewUpdated, // published library (not many followers, recently updated) - test ordering
          libPublishedCollabWithMe, // published collaborative library owned by me
          libPublishedCollabWithOther, // published collaborative library owned by another
          libPublishedHidden, // published library hidden on profile (owner has hidden membership)
          libPublishedEmpty, // published library with 0 keeps
          libPrivate, // private library
          libPrivateFollower, // private library with follower
          libPrivateCollab // private collaborative library with collaborator
        )

        (owner, other, ownerLibs)
      }

      val (mainLib, secretLib) = libraryCommander.internSystemGeneratedLibraries(owner.id.get)
      val allLibs = userCreatedLibs ++ Seq(mainLib, secretLib)

      val paginator = Paginator(0, 15)
      val imageSize = ImageSize("100x100")
      val ord = profileLibraryOrdering

      // test self viewer (should see all libraries)
      val viewSelf = commander.getOwnLibrariesForSelf(owner, paginator, imageSize)
      viewSelf.map(_.slug.value) === Seq("main", "secret", "published-lots", "published-few-updated", "published-few", "private-collab", "private-follower", "published-collab-with-other", "published-collab-with-me", "private", "published-hidden", "published-empty")
      commander.countLibraries(owner.id.get, Some(owner.id.get)) === (11, 1, 0, Some(0))

      // test anonymous viewer (see only published libraries, non-hidden, non-empty)
      val viewAnonymous = commander.getOwnLibraries(owner, None, paginator, imageSize)
      viewAnonymous.map(_.slug.value) === Seq("published-lots", "published-few-updated", "published-few", "published-collab-with-other", "published-collab-with-me")
      commander.countLibraries(owner.id.get, None) === (4, 1, 0, None)

      // test friend viewer (see only published libraries - non-hidden, non-empty & private libraries friend has membership to)
      val viewFriend = commander.getOwnLibraries(owner, Some(other), paginator, imageSize)
      viewFriend.map(_.slug.value) === Seq("published-lots", "published-few-updated", "published-few", "private-collab", "private-follower", "published-collab-with-other", "published-collab-with-me")
      commander.countLibraries(owner.id.get, Some(other.id.get)) === (6, 1, 0, None)
    }
  }

  "get owner library counts" in {
    withDb(modules: _*) { implicit injector =>
      db.readWrite { implicit s =>
        val libs = libraries(5)
        val user1 = libs.take(3).map { _.withUser(Id[User](1)).published() }.saved
        val user2 = libs.drop(3).map { _.withUser(Id[User](2)).published() }.saved
      }

      db.readOnlyMaster { implicit s =>
        val users = Set(1, 2, 100).map { Id[User](_) }
        val map = libraryRepo.getOwnerLibraryCounts(users)
        map === Map(Id[User](1) -> 3, Id[User](2) -> 2, Id[User](100) -> 0)

        val map2 = libraryRepo.getOwnerLibraryCounts(Set[Id[User]]())
        map2.size === 0
      }
    }
  }

  "get libraries user is invited to" in {
    withDb(modules: _*) { implicit injector =>
      implicit val config = inject[PublicIdConfiguration]
      val commander = inject[UserProfileCommander]
      val (user1, user2, user3, libs) = db.readWrite { implicit s =>
        val user1 = user().saved
        val user2 = user().saved
        val user3 = user().saved
        val lib1 = libraries(2).map(_.published().withUser(user1)).saved.head.savedInvitation(user2)
        val lib2 = libraries(2).map(_.secret().withUser(user1)).saved.head.savedInvitation(user2).savedInvitation(user3)
        val lib3 = libraries(2).map(_.published().withUser(user2)).saved.head.savedInvitation(user1)
        invite().fromLibraryOwner(library.published().withUser(user2).saved).saved.savedStateChange(LibraryInviteStates.ACCEPTED)
        invite().fromLibraryOwner(library.published().withUser(user2).saved).declined.saved
        libraries(10).map(_.published().withUser(user1)).saved
        libraries(10).map(_.published().withUser(user2)).saved
        (user1, user2, user3, lib1 :: lib2 :: lib3 :: Nil)
      }

      commander.getInvitedLibraries(user1, None, Paginator(0, 5), ImageSize("100x100")).size === 0
      commander.getInvitedLibraries(user1, Some(user2), Paginator(0, 5), ImageSize("100x100")).size === 0

      val libs1 = commander.getInvitedLibraries(user1, Some(user1), Paginator(0, 5), ImageSize("100x100"))
      libs1.size === 1
      libs1.map(_.id).head === Library.publicId(libs(2).id.get)

      val libs2 = commander.getInvitedLibraries(user2, Some(user2), Paginator(0, 5), ImageSize("100x100"))
      libs2.size === 2
      libs2.map(_.id) === libs.take(2).reverse.map(_.id.get).map(Library.publicId)

      val libs3 = commander.getInvitedLibraries(user3, Some(user3), Paginator(0, 5), ImageSize("100x100"))
      libs3.size === 1
      libs3.map(_.id).head === Library.publicId(libs(1).id.get)

    }
  }

  "getFollowingLibraries for anonymous" in {
    withDb(modules: _*) { implicit injector =>
      implicit val config = inject[PublicIdConfiguration]
      val commander = inject[UserProfileCommander]
      val (owner, other, allLibs) = db.readWrite { implicit s =>
        val owner = user().saved
        val other = user().saved
        val otherFollows1 = libraries(2).map(_.published().withUser(owner)).saved.head.savedFollowerMembership(other)
        val otherFollows2 = libraries(2).map(_.secret().withUser(owner)).saved.head.savedFollowerMembership(other)
        libraries(2).map(_.published().withUser(other)).saved.head.savedFollowerMembership(owner)
        val otherFollows3 = libraries(10).map(_.published().withUser(owner)).saved.map(_.savedFollowerMembership(other))
        libraries(10).map(_.published().withUser(other)).saved
        (owner, other, List(otherFollows1) ++ otherFollows3)
      }

      val libsP1 = commander.getFollowingLibraries(other, None, Paginator(0, 5), ImageSize("100x100"))
      libsP1.size === 5
      libsP1.map(_.id) === allLibs.reverse.take(5).map(_.id.get).map(Library.publicId)

      val libsP2 = commander.getFollowingLibraries(other, None, Paginator(1, 5), ImageSize("100x100"))
      libsP2.size === 5
      libsP2.map(_.id) === allLibs.reverse.drop(5).take(5).map(_.id.get).map(Library.publicId)

      val libsP3 = commander.getFollowingLibraries(other, None, Paginator(2, 5), ImageSize("100x100"))
      libsP3.size === 1
      libsP3.map(_.id) === allLibs.reverse.drop(10).take(5).map(_.id.get).map(Library.publicId)
    }
  }

  "getFollowingLibraries for self" in {
    withDb(modules: _*) { implicit injector =>
      implicit val config = inject[PublicIdConfiguration]
      val commander = inject[UserProfileCommander]
      val (owner, other, allLibs) = db.readWrite { implicit s =>
        val owner = user().saved
        val other = user().saved
        val otherFollows1 = libraries(2).map(_.published().withUser(owner)).saved.head.savedFollowerMembership(other)
        val otherFollows2 = libraries(2).map(_.secret().withUser(owner)).saved.head.savedFollowerMembership(other)
        libraries(2).map(_.published().withUser(other)).saved.head.savedFollowerMembership(owner)
        val otherFollows3 = libraries(10).map(_.published().withUser(owner)).saved.map(_.savedFollowerMembership(other))
        libraries(10).map(_.published().withUser(other)).saved
        (owner, other, List(otherFollows1, otherFollows2) ++ otherFollows3)
      }

      val libs = commander.getFollowingLibraries(other, Some(other), Paginator(0, 500), ImageSize("100x100"))
      libs.size === 12
      libs.head.owner.externalId === owner.externalId
      libs.map(_.id) === allLibs.reverse.map(_.id.get).map(Library.publicId)
    }
  }

  "getFollowingLibraries for other" in {
    withDb(modules: _*) { implicit injector =>
      implicit val config = inject[PublicIdConfiguration]
      val userProfileCommander = inject[UserProfileCommander]
      val (user1, user2, user3, follows1, follows2, follows3, follows4) = db.readWrite { implicit s =>
        val user1 = user().saved
        val user2 = user().saved
        val user3 = user().saved
        val follows1 = libraries(2).map(_.published().withUser(user1).withName("a")).saved.head.savedFollowerMembership(user2)
        val follows2 = libraries(2).map(_.secret().withUser(user1).withName("b")).saved.head.savedFollowerMembership(user2).savedFollowerMembership(user3)
        val follows3 = libraries(10).map(_.secret().withUser(user3).withName("c")).saved.map(_.savedFollowerMembership(user2))
        libraries(2).map(_.published().withUser(user2).withName("d")).saved.head.savedFollowerMembership(user1)
        val follows4 = libraries(10).map(_.published().withUser(user1).withName("e")).saved.map(_.savedFollowerMembership(user2))
        libraries(10).map(_.published().withUser(user2).withName("f")).saved
        (user1, user2, user3, follows1, follows2, follows3, follows4)
      }

      val self = userProfileCommander.getFollowingLibraries(user2, Some(user2), Paginator(0, 500), ImageSize("100x100"))
      self.size === 22
      self.map(_.id) === (List(follows1, follows2) ++ follows3 ++ follows4).reverse.map(_.id.get).map(Library.publicId)

      val libs1 = userProfileCommander.getFollowingLibraries(user2, Some(user1), Paginator(0, 500), ImageSize("100x100"))
      libs1.size === 12
      libs1.map(_.id) === (List(follows1, follows2) ++ follows4).reverse.map(_.id.get).map(Library.publicId)
    }
  }

  "getConnectionsSortedByRelationship" in {
    withDb(modules: _*) { implicit injector =>
      val (owner, viewer, user1, user2, user3, user4, user5, user6, user7) = db.readWrite { implicit s =>
        val user1 = user().saved
        val user2 = user().saved
        val user3 = user().saved
        val user4 = user().saved
        val user5 = user().saved
        val user6 = user().saved
        val user7 = user().saved

        val owner = user().saved
        val viewer = user().saved

        connect(viewer -> owner).saved //mc

        connect(viewer -> user1).saved //mc
        connect(viewer -> user4).saved //mc - 0.1
        connect(viewer -> user5).saved
        connect(viewer -> user6).saved //mc

        connect(owner -> user1).saved //mc
        connect(owner -> user2).saved //   - 0.2
        connect(owner -> user3).saved //   - 0.3
        connect(owner -> user4).saved //mc - 0.1
        connect(owner -> user6).saved //mc
        connect(owner -> user7).saved
        (owner, viewer, user1, user2, user3, user4, user5, user6, user7)
      }

      val relationship = SociallyRelatedEntities(
        RelatedEntities[User, User](owner.id.get, Seq(user4.id.get -> .1, user5.id.get -> .4, user2.id.get -> .2, user3.id.get -> .3)),
        RelatedEntities[User, SocialUserInfo](owner.id.get, Seq.empty),
        RelatedEntities[User, SocialUserInfo](owner.id.get, Seq.empty),
        RelatedEntities[User, EmailAccountInfo](owner.id.get, Seq.empty)
      )

      Await.result(inject[GraphServiceClient].getSociallyRelatedEntities(viewer.id.get), Duration.Inf) === None
      inject[FakeGraphServiceClientImpl].setSociallyRelatedEntities(viewer.id.get, relationship)
      Await.result(inject[FakeGraphServiceClientImpl].getSociallyRelatedEntities(viewer.id.get), Duration.Inf).get === relationship
      Await.result(inject[GraphServiceClient].getSociallyRelatedEntities(viewer.id.get), Duration.Inf).get === relationship
      val commander = inject[UserProfileCommander]
      val connections = Await.result(commander.getConnectionsSortedByRelationship(viewer.id.get, owner.id.get), Duration.Inf)

      connections.map(_.userId) === Seq(viewer, user4, user1, user6, user3, user2, user7).map(_.id.get)
      connections.map(_.connected) === Seq(true, true, true, true, false, false, false)

      val selfConnections = Await.result(commander.getConnectionsSortedByRelationship(owner.id.get, owner.id.get), Duration.Inf)

      selfConnections.map(_.userId) === Seq(user4, viewer, user3, user1, user7, user6, user2).map(_.id.get)
      selfConnections.map(_.connected) === Seq(true, true, true, true, true, true, true)

      val repo = inject[UserConnectionRepo]

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(owner.id.get) === true
        repo.getConnectedUsers(owner.id.get).contains(viewer.id.get) === true
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, owner.id.get))
        connections(viewer.id.get).contains(owner.id.get) === true
        connections(owner.id.get).contains(viewer.id.get) === true
      }

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(user1.id.get) === true
        repo.getConnectedUsers(user1.id.get).contains(viewer.id.get) === true
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, user1.id.get))
        connections(viewer.id.get).contains(user1.id.get) === true
        connections(user1.id.get).contains(viewer.id.get) === true
      }

      db.readWrite { implicit s =>
        repo.unfriendConnections(owner.id.get, Set(viewer.id.get)) === 1
        repo.unfriendConnections(viewer.id.get, Set(user1.id.get)) === 1
      }

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(owner.id.get) === false
        repo.getConnectedUsers(owner.id.get).contains(viewer.id.get) === false
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, owner.id.get))
        connections(viewer.id.get).contains(owner.id.get) === false
        connections(owner.id.get).contains(viewer.id.get) === false
      }

      db.readWrite { implicit s =>
        repo.getConnectedUsers(viewer.id.get).contains(user1.id.get) === false
        repo.getConnectedUsers(user1.id.get).contains(viewer.id.get) === false
        val connections = repo.getConnectedUsersForUsers(Set(viewer.id.get, user1.id.get))
        connections(viewer.id.get).contains(user1.id.get) === false
        connections(user1.id.get).contains(viewer.id.get) === false
      }

      val connections2 = Await.result(commander.getConnectionsSortedByRelationship(viewer.id.get, owner.id.get), Duration.Inf)

      connections2.map(_.userId) === Seq(user4, user6, user3, user2, user1, user7).map(_.id.get)
      connections2.map(_.connected) === Seq(true, true, false, false, false, false)

      val selfConnections2 = Await.result(commander.getConnectionsSortedByRelationship(owner.id.get, owner.id.get), Duration.Inf)

      selfConnections2.map(_.userId) === Seq(user4, user3, user1, user7, user6, user2).map(_.id.get)
      selfConnections2.map(_.connected) === Seq(true, true, true, true, true, true)

    }

  }

}
