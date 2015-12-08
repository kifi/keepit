package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class PermissionCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "PermissionCommander" should {
    "handle system library permissions" in {
      "for owners and randos" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val systemLibs = {
              val mainLib = LibraryFactory.library().withOwner(owner).withKind(LibraryKind.SYSTEM_MAIN).saved
              val secretLib = LibraryFactory.library().withOwner(owner).withKind(LibraryKind.SYSTEM_SECRET).saved
              val readItLaterLib = LibraryFactory.library().withOwner(owner).withKind(LibraryKind.SYSTEM_READ_IT_LATER).saved
              Set(mainLib, secretLib, readItLaterLib)
            }
            for (lib <- systemLibs) {
              permissionCommander.getLibraryPermissions(lib.id.get, Some(owner.id.get)) === Set(
                LibraryPermission.VIEW_LIBRARY,
                LibraryPermission.ADD_KEEPS,
                LibraryPermission.EDIT_OWN_KEEPS,
                LibraryPermission.REMOVE_OWN_KEEPS,
                LibraryPermission.ADD_COMMENTS
              )
              permissionCommander.getLibraryPermissions(lib.id.get, Some(rando.id.get)) === Set.empty
              permissionCommander.getLibraryPermissions(lib.id.get, None) === Set.empty
            }
          }
          1 === 1
        }
      }
      "for org owners and members" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val systemLibs = {
              val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
              val orgGeneralLib = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL).head
              Set(orgGeneralLib)
            }
            for (lib <- systemLibs) {
              permissionCommander.getLibraryPermissions(lib.id.get, Some(owner.id.get)) === Set(
                LibraryPermission.VIEW_LIBRARY,
                LibraryPermission.ADD_KEEPS,
                LibraryPermission.EDIT_OWN_KEEPS,
                LibraryPermission.CREATE_SLACK_INTEGRATION,
                LibraryPermission.REMOVE_OWN_KEEPS,
                LibraryPermission.ADD_COMMENTS
              )
              permissionCommander.getLibraryPermissions(lib.id.get, Some(member.id.get)) === Set(
                LibraryPermission.VIEW_LIBRARY,
                LibraryPermission.ADD_KEEPS,
                LibraryPermission.EDIT_OWN_KEEPS,
                LibraryPermission.CREATE_SLACK_INTEGRATION,
                LibraryPermission.REMOVE_OWN_KEEPS,
                LibraryPermission.ADD_COMMENTS
              )
              permissionCommander.getLibraryPermissions(lib.id.get, Some(rando.id.get)) === Set.empty
              permissionCommander.getLibraryPermissions(lib.id.get, None) === Set.empty
            }
          }
          1 === 1
        }
      }
    }
    "handle personal user created library permissions" in {
      def setupLibs()(implicit injector: Injector, session: RWSession) = {
        val owner = UserFactory.user().saved
        val collab = UserFactory.user().saved
        val follower = UserFactory.user().saved
        val rando = UserFactory.user().saved
        val publicLib = LibraryFactory.library().withKind(LibraryKind.USER_CREATED).withVisibility(LibraryVisibility.PUBLISHED).withOwner(owner).withCollaborators(Seq(collab)).withFollowers(Seq(follower)).saved
        val secretLib = LibraryFactory.library().withKind(LibraryKind.USER_CREATED).withVisibility(LibraryVisibility.SECRET).withOwner(owner).withCollaborators(Seq(collab)).withFollowers(Seq(follower)).saved
        (owner.id, collab.id, follower.id, rando.id, Option.empty[Id[User]], publicLib, secretLib)
      }
      "view permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            val whoCanViewPublic = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.VIEW_LIBRARY) }
            whoCanViewPublic === Set(owner, collab, follower, rando, noone)

            val whoCanViewSecret = all.filter { x => permissionCommander.getLibraryPermissions(secretLib.id.get, x).contains(LibraryPermission.VIEW_LIBRARY) }
            whoCanViewSecret === Set(owner, collab, follower)
          }
        }
      }
      "add keep permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanKeep = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.ADD_KEEPS) }
              whoCanKeep === Set(owner, collab)
            }
          }
          1 === 1
        }
      }
      "add comment permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanKeep = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.ADD_COMMENTS) }
              whoCanKeep === Set(owner, collab)
            }
          }
          1 === 1
        }
      }
      "edit library permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanEditLibrary = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.EDIT_LIBRARY) }
              whoCanEditLibrary === Set(owner)
            }
          }
          1 === 1
        }
      }
      "move library permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanPublishLibrary = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.MOVE_LIBRARY) }
              whoCanPublishLibrary === Set(owner)
            }
          }
          1 === 1
        }
      }
      "delete library permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanPublishLibrary = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.DELETE_LIBRARY) }
              whoCanPublishLibrary === Set(owner)
            }
          }
          1 === 1
        }
      }
      "remove members permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanRemoveMembers = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.REMOVE_MEMBERS) }
              whoCanRemoveMembers === Set(owner)
            }
          }
          1 === 1
        }
      }
      "invite followers permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            val whoCanInvitePublic = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.INVITE_FOLLOWERS) }
            whoCanInvitePublic === Set(owner, collab, follower)

            val whoCanInviteSecret = all.filter { x => permissionCommander.getLibraryPermissions(secretLib.id.get, x).contains(LibraryPermission.INVITE_FOLLOWERS) }
            whoCanInviteSecret === Set(owner, collab)
          }
          1 === 1
        }
      }
      "invite collaborators permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (owner, collab, follower, rando, noone, publicLib, secretLib) = setupLibs()
            val all = Set(owner, collab, follower, rando, noone)

            Set(publicLib, secretLib).foreach { lib =>
              val whoCanInviteCollaborators = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.INVITE_COLLABORATORS) }
              whoCanInviteCollaborators === Set(owner, collab)
            }
          }
          1 === 1
        }
      }
    }
    "handle in-organization library permissions" in {
      def setupLibs()(implicit injector: Injector, session: RWSession) = {
        val orgOwner = UserFactory.user().saved
        val libOwner = UserFactory.user().saved
        val collab = UserFactory.user().saved
        val member = UserFactory.user().saved
        val follower = UserFactory.user().saved
        val rando = UserFactory.user().saved

        val org = OrganizationFactory.organization().withOwner(orgOwner).withMembers(Seq(libOwner, collab, member, follower)).saved
        val publicLib = LibraryFactory.library().withKind(LibraryKind.USER_CREATED).withOrganization(org).withVisibility(LibraryVisibility.PUBLISHED).withOwner(libOwner).withCollaborators(Seq(collab)).withFollowers(Seq(follower)).saved
        val orgLib = LibraryFactory.library().withKind(LibraryKind.USER_CREATED).withOrganization(org).withVisibility(LibraryVisibility.ORGANIZATION).withOwner(libOwner).withCollaborators(Seq(collab)).withFollowers(Seq(follower)).saved
        val secretLib = LibraryFactory.library().withKind(LibraryKind.USER_CREATED).withOrganization(org).withVisibility(LibraryVisibility.SECRET).withOwner(libOwner).withCollaborators(Seq(collab)).withFollowers(Seq(follower)).saved
        val openCollabLib = LibraryFactory.library().withOwner(libOwner).withCollaborators(Seq(collab)).withOrganization(org).withOrgMemberCollaborativePermission(Some(LibraryAccess.READ_WRITE)).saved
        val openCommentLib = LibraryFactory.library().withOwner(libOwner).published().withLibraryCommentPermissions(LibraryCommentPermissions.ANYONE).saved
        (orgOwner.id, libOwner.id, collab.id, follower.id, member.id, rando.id, Option.empty[Id[User]], publicLib, orgLib, secretLib, openCollabLib, openCommentLib)
      }
      "view permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (orgOwner, libOwner, collab, follower, member, rando, noone, publicLib, orgLib, secretLib, _, _) = setupLibs()
            val all = Set(orgOwner, libOwner, collab, follower, member, rando, noone)

            val whoCanViewPublic = all.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.VIEW_LIBRARY) }
            whoCanViewPublic === all

            val whoCanViewOrg = all.filter { x => permissionCommander.getLibraryPermissions(orgLib.id.get, x).contains(LibraryPermission.VIEW_LIBRARY) }
            whoCanViewOrg === Set(orgOwner, libOwner, collab, follower, member)

            val whoCanViewSecret = all.filter { x => permissionCommander.getLibraryPermissions(secretLib.id.get, x).contains(LibraryPermission.VIEW_LIBRARY) }
            whoCanViewSecret === Set(libOwner, collab, follower)
          }
        }
      }
      "edit library permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (orgOwner, libOwner, collab, follower, member, rando, noone, publicLib, orgLib, secretLib, _, _) = setupLibs()
            val all = Set(orgOwner, libOwner, collab, follower, member, rando, noone)

            Set(publicLib, orgLib, secretLib).foreach { lib =>
              val whoCanEdit = all.filter { x => permissionCommander.getLibraryPermissions(lib.id.get, x).contains(LibraryPermission.EDIT_LIBRARY) }
              whoCanEdit === Set(libOwner)
            }
            1 === 1
          }
        }
      }
      "add comment permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (orgOwner, libOwner, collab, follower, member, rando, noone, publicLib, orgLib, secretLib, openCollabLib, openCommentLib) = setupLibs()
            val all = Set(orgOwner, libOwner, collab, follower, member, rando, noone)

            Set(publicLib, orgLib, secretLib).foreach { lib =>
              val whoCanEdit = all.filter { x => permissionCommander.getLibraryPermissions(lib.id.get, x).contains(LibraryPermission.ADD_COMMENTS) }
              whoCanEdit === Set(libOwner, collab)
            }

            val whoCanEdit2 = all.filter { x => permissionCommander.getLibraryPermissions(openCollabLib.id.get, x).contains(LibraryPermission.ADD_COMMENTS) }
            whoCanEdit2 === Set(orgOwner, libOwner, collab, member, follower) // all org members can comment

            val whoCanEdit3 = all.filter { x => permissionCommander.getLibraryPermissions(openCommentLib.id.get, x).contains(LibraryPermission.ADD_COMMENTS) }
            whoCanEdit3 === all // anyone can comment on a public library
          }
        }
      }
    }
    "propagate user permissions into libraries" in {
      "let pro users create slack integrations (unless prohibited)" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).withSlackProhibited().saved

            val privateLib = LibraryFactory.library().withOwner(owner).secret().saved
            val publicLib = LibraryFactory.library().withOwner(owner).published().saved
            val orgLib = LibraryFactory.library().withOwner(owner).withOrganization(org).orgVisible().saved

            val users = Set(owner.id, member.id, rando.id, None)
            val whoCanIntegratePrivate = users.filter { x => permissionCommander.getLibraryPermissions(privateLib.id.get, x).contains(LibraryPermission.CREATE_SLACK_INTEGRATION) }
            whoCanIntegratePrivate === Set(owner.id)
            val whoCanIntegratePublic = users.filter { x => permissionCommander.getLibraryPermissions(publicLib.id.get, x).contains(LibraryPermission.CREATE_SLACK_INTEGRATION) }
            whoCanIntegratePublic === Set(owner.id, member.id)
            val whoCanIntegrateOrg = users.filter { x => permissionCommander.getLibraryPermissions(orgLib.id.get, x).contains(LibraryPermission.CREATE_SLACK_INTEGRATION) }
            whoCanIntegrateOrg === Set.empty

            1 === 1
          }
        }
      }
    }
  }
}
