package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class LibraryMembershipCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  "LibraryMembershipCommander" should {
    "when updateMembership is called:" in {
      "succeed for member starring themselves" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup
          val result = commander.updateMembership(member.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, starred = Some(LibraryPriority.STARRED)))
          result.isRight === true
          result.right.get.priority === LibraryPriority.STARRED
        }
      }
      "fail for member starring a lib other member" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup
          val result = commander.updateMembership(owner.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, starred = Some(LibraryPriority.STARRED)))
          result.isLeft === true
          result.left.get.message === "permission_denied"
        }
      }

      "succeed for owner of lib changing access" in {
        withDb() { implicit injector =>
          val (owner, member, non_member, lib) = setup
          val result = commander.updateMembership(member.id.get, ModifyLibraryMembershipRequest(userId = member.id.get, libraryId = lib.id.get, starred = Some(LibraryPriority.STARRED)))
          result.isRight === true
          result.right.get.priority === LibraryPriority.STARRED
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
