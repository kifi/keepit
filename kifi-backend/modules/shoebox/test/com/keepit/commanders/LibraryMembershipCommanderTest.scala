package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class LibraryMembershipCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  "LibraryMembershipCommander" should {

    "update membership to a library" in {
      withDb() { implicit injector =>
        val libraryMembershipCommander = inject[LibraryMembershipCommander]
        val (user1, user2, user3, user4, lib1) = db.readWrite { implicit s =>
          val user1 = user().withUsername("nickfury").saved
          val user2 = user().withUsername("quicksilver").saved
          val user3 = user().withUsername("scarletwitch").saved
          val user4 = user().withUsername("somerandomshieldagent").saved
          val lib1 = library().withOwner(user1).saved // user1 owns lib1
          membership().withLibraryCollaborator(lib1, user2).saved // user2 is a collaborator lib1 (has read_write access)
          membership().withLibraryFollower(lib1, user3).saved // user3 is a follower to lib1 (has read_only access)

          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get.access === LibraryAccess.OWNER
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get).get.access === LibraryAccess.READ_WRITE
          (user1, user2, user3, user4, lib1)
        }

        val userId1 = user1.id.get // owner
        val userId2 = user2.id.get // collaborator
        val userId3 = user3.id.get // follower
        val userId4 = user4.id.get // just a nobody

        // test changing owner access (error)
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId1, None).isRight === false

        // test changing membership that does not exist (error)
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId4, None).isRight === false

        // test changing access to owner (error)
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId2, Some(LibraryAccess.OWNER)).isRight === false

        // test owner demoting access
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId2, Some(LibraryAccess.READ_ONLY)) must beRight
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId2).get.access === LibraryAccess.READ_ONLY
        }

        // test owner promoting access
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId2, Some(LibraryAccess.READ_WRITE)) must beRight
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId2).get.access === LibraryAccess.READ_WRITE
        }

        // test collaborator promoting access
        libraryMembershipCommander.updateLibraryMembershipAccess(userId2, lib1.id.get, userId3, Some(LibraryAccess.READ_WRITE)) must beRight
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId3).get.access === LibraryAccess.READ_WRITE
        }

        // test collaborator demoting access
        libraryMembershipCommander.updateLibraryMembershipAccess(userId2, lib1.id.get, userId3, Some(LibraryAccess.READ_ONLY)) must beRight
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId3).get.access === LibraryAccess.READ_ONLY
        }

        // test collaborator promoting access (but library does not allow collabs to invite)
        db.readWrite { implicit s =>
          libraryRepo.save(lib1.copy(whoCanInvite = Some(LibraryInvitePermissions.OWNER)))
        }
        libraryMembershipCommander.updateLibraryMembershipAccess(userId2, lib1.id.get, userId3, Some(LibraryAccess.READ_WRITE)).isRight === false
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId3).get.access === LibraryAccess.READ_ONLY
        }

        // test collaborator removing access
        libraryMembershipCommander.updateLibraryMembershipAccess(userId2, lib1.id.get, userId3, None) must beRight
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId3) === None
        }

        // test owner removing access
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId2, None) must beRight
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, userId2) === None
        }

        // test non-active membership (after removing access) (error)
        libraryMembershipCommander.updateLibraryMembershipAccess(userId1, lib1.id.get, userId2, None).isRight === false

      }
    }

    "when updateMembership is called:" in {
      "succeed for a user changing priority for themselves" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup
          val result = commander.updateMembership(member.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, priority = Some(1)))
          result.isRight === true
          result.right.get.priority === 1
        }
      }
      "fail for member changing library priority for another member" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup
          val result = commander.updateMembership(owner.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, priority = Some(1)))
          result.isLeft === true
          result.left.get.message === "permission_denied"
        }
      }

      "succeed for owner of lib changing access" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup
          val result = commander.updateMembership(member.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, priority = Some(1)))
          result.isRight === true
          result.right.get.priority === 1
        }
      }
      "fail for anyone else changing access" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup

          // cannot demote himself
          val cannot_demote_himself = commander.updateMembership(owner.id.get, ModifyLibraryMembershipRequest(userId = owner.id.get, libraryId = lib.id.get, access = Some(LibraryAccess.READ_WRITE)))
          cannot_demote_himself.isLeft === true
          cannot_demote_himself.left.get.message === "permission_denied"

          val cannot_change_other_access = commander.updateMembership(member.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, access = Some(LibraryAccess.READ_WRITE)))
          cannot_change_other_access.isLeft === true
        }
      }
    }
  }

  def repo(implicit injector: Injector) = inject[LibraryMembershipRepo]
  def commander(implicit injector: Injector) = inject[LibraryMembershipCommander]
  def setup(implicit injector: Injector) = db.readWrite { implicit session =>
    val owner = UserFactory.user().saved
    val member = UserFactory.user().saved
    val non_member = UserFactory.user().saved
    val library = inject[LibraryRepo].save(Library(name = "Earth", ownerId = owner.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("Earth"), memberCount = 1))
    repo.save(LibraryMembership(libraryId = library.id.get, userId = owner.id.get, access = LibraryAccess.OWNER))
    repo.save(LibraryMembership(libraryId = library.id.get, userId = member.id.get, access = LibraryAccess.READ_WRITE))
    (owner, member, non_member, library)
  }
}
