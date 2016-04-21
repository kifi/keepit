package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
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
  object TestSetup {
    case class Users(owner: User, admin: User, member: User, collab: User, follower: User, rando: User)
    case class Libraries(public: Library, secret: Library, orgVisible: Library, main: Library, orgGeneral: Library, slack: Library)
    case class Data(users: Users, libs: Libraries, org: Organization) {
      def owner = Some(users.owner.id.get)
      def admin = Some(users.admin.id.get)
      def member = Some(users.member.id.get)
      def collab = Some(users.collab.id.get)
      def follower = Some(users.follower.id.get)
      def rando = Some(users.rando.id.get)
      def noone = None
      def everyone: Set[Option[Id[User]]] = Set(owner, admin, member, collab, follower, rando, noone)
      def allUsers: Set[Option[Id[User]]] = Set(owner, admin, member, collab, follower, rando)
      def libMembers: Set[Option[Id[User]]] = Set(owner, collab, follower)
      def orgMembers: Set[Option[Id[User]]] = Set(owner, admin, member)

      def public = libs.public.id.get
      def secret = libs.secret.id.get
      def main = libs.main.id.get
      def orgVisible = libs.orgVisible.id.get
      def orgGeneral = libs.orgGeneral.id.get
      def slack = libs.slack.id.get
    }
  }
  def runPermissionsTest(permission: LibraryPermission)(tests: (Id[Library], Set[Option[Id[User]]])*)(implicit injector: Injector, session: RSession, data: TestSetup.Data): Unit = {
    tests.foreach {
      case (lib, expected) =>
        data.everyone.filter(x => permissionCommander.getLibraryPermissions(lib, x).contains(permission)) === expected
    }
  }
  def setup()(implicit injector: Injector, session: RWSession): TestSetup.Data = {
    val users = TestSetup.Users(
      owner = UserFactory.user().saved,
      admin = UserFactory.user().saved,
      member = UserFactory.user().saved,
      collab = UserFactory.user().saved,
      follower = UserFactory.user().saved,
      rando = UserFactory.user().saved
    )
    val org = OrganizationFactory.organization().withOwner(users.owner).withAdmins(Seq(users.admin)).withMembers(Seq(users.member)).withStrongAdmins().saved
    val libs = TestSetup.Libraries(
      public = LibraryFactory.library().withOwner(users.owner).withCollaborators(Seq(users.collab)).withFollowers(Seq(users.follower)).withVisibility(LibraryVisibility.PUBLISHED).saved,
      secret = LibraryFactory.library().withOwner(users.owner).withCollaborators(Seq(users.collab)).withFollowers(Seq(users.follower)).withVisibility(LibraryVisibility.SECRET).saved,
      orgVisible = LibraryFactory.library().withOrganization(org).withOwner(users.owner).withCollaborators(Seq(users.collab)).withFollowers(Seq(users.follower)).withVisibility(LibraryVisibility.ORGANIZATION).saved,
      orgGeneral = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL).head,
      main = LibraryFactory.library().withOwner(users.owner).withKind(LibraryKind.SYSTEM_MAIN).saved,
      slack = LibraryFactory.library().withOrganization(org).withOwner(users.owner).withCollaborators(Seq(users.collab)).withFollowers(Seq(users.follower)).withVisibility(LibraryVisibility.ORGANIZATION).withKind(LibraryKind.SLACK_CHANNEL).saved
    )
    TestSetup.Data(users, libs, org)
  }

  "PermissionCommander" should {
    "handle system library permissions" in {
      "for owners and randos" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit s =>
            val data = setup()
            for (lib <- List(data.libs.main)) {
              permissionCommander.getLibraryPermissions(lib.id.get, Some(data.users.owner.id.get)) === Set(
                LibraryPermission.VIEW_LIBRARY,
                LibraryPermission.ADD_KEEPS,
                LibraryPermission.EDIT_OWN_KEEPS,
                LibraryPermission.EDIT_OTHER_KEEPS,
                LibraryPermission.REMOVE_OWN_KEEPS,
                LibraryPermission.REMOVE_OTHER_KEEPS,
                LibraryPermission.ADD_COMMENTS,
                LibraryPermission.CREATE_SLACK_INTEGRATION
              )
              permissionCommander.getLibraryPermissions(lib.id.get, Some(data.users.rando.id.get)) === Set.empty
              permissionCommander.getLibraryPermissions(lib.id.get, None) === Set.empty
            }
          }
          1 === 1
        }
      }
      "for org owners and members" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val data = setup()
            for (lib <- List(data.orgGeneral)) {
              permissionCommander.getLibraryPermissions(lib, data.owner) === Set(
                LibraryPermission.VIEW_LIBRARY,
                LibraryPermission.ADD_KEEPS,
                LibraryPermission.EDIT_OWN_KEEPS,
                LibraryPermission.EDIT_OTHER_KEEPS,
                LibraryPermission.CREATE_SLACK_INTEGRATION,
                LibraryPermission.REMOVE_OWN_KEEPS,
                LibraryPermission.REMOVE_OTHER_KEEPS,
                LibraryPermission.ADD_COMMENTS
              )
              permissionCommander.getLibraryPermissions(lib, data.member) === Set(
                LibraryPermission.VIEW_LIBRARY,
                LibraryPermission.ADD_KEEPS,
                LibraryPermission.EDIT_OWN_KEEPS,
                LibraryPermission.EDIT_OTHER_KEEPS,
                LibraryPermission.CREATE_SLACK_INTEGRATION,
                LibraryPermission.REMOVE_OWN_KEEPS,
                LibraryPermission.REMOVE_OTHER_KEEPS,
                LibraryPermission.ADD_COMMENTS
              )
              permissionCommander.getLibraryPermissions(lib, data.rando) === Set.empty
              permissionCommander.getLibraryPermissions(lib, data.noone) === Set.empty
            }
          }
          1 === 1
        }
      }
    }
    "handle personal user created library permissions" in {
      "a bunch of tests" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            implicit val data = setup()
            runPermissionsTest(LibraryPermission.VIEW_LIBRARY)(
              data.main -> Set(data.owner),
              data.secret -> Set(data.owner, data.collab, data.follower),
              data.orgVisible -> (data.orgMembers ++ data.libMembers),
              data.public -> data.everyone
            )
            runPermissionsTest(LibraryPermission.ADD_KEEPS)(
              data.main -> Set(data.owner),
              data.secret -> Set(data.owner, data.collab),
              data.orgVisible -> Set(data.owner, data.collab),
              data.public -> Set(data.owner, data.collab)
            )
            runPermissionsTest(LibraryPermission.ADD_COMMENTS)(
              data.main -> Set(data.owner),
              data.secret -> data.libMembers,
              data.orgVisible -> (data.orgMembers ++ data.libMembers),
              data.public -> data.everyone
            )
            runPermissionsTest(LibraryPermission.EDIT_LIBRARY)(
              data.main -> Set.empty,
              data.secret -> Set(data.owner),
              data.orgVisible -> Set(data.owner, data.admin),
              data.public -> Set(data.owner)
            )
            runPermissionsTest(LibraryPermission.MOVE_LIBRARY)(
              data.main -> Set.empty,
              data.orgGeneral -> Set.empty,
              data.secret -> Set(data.owner),
              data.orgVisible -> Set(data.owner, data.admin)
            )
            runPermissionsTest(LibraryPermission.DELETE_LIBRARY)(
              data.main -> Set.empty,
              data.orgGeneral -> Set.empty,
              data.secret -> Set(data.owner),
              data.orgVisible -> Set(data.owner, data.admin)
            )
            runPermissionsTest(LibraryPermission.REMOVE_MEMBERS)(
              data.main -> Set.empty,
              data.orgGeneral -> Set.empty,
              data.secret -> Set(data.owner),
              data.orgVisible -> Set(data.owner)
            )
            runPermissionsTest(LibraryPermission.INVITE_FOLLOWERS)(
              data.main -> Set.empty,
              data.orgGeneral -> Set.empty,
              data.secret -> Set(data.owner, data.collab),
              data.orgVisible -> (data.libMembers ++ data.orgMembers),
              data.public -> data.libMembers
            )
            runPermissionsTest(LibraryPermission.INVITE_COLLABORATORS)(
              data.main -> Set.empty,
              data.orgGeneral -> Set.empty,
              data.secret -> Set(data.owner, data.collab),
              data.orgVisible -> Set(data.owner, data.collab),
              data.public -> Set(data.owner, data.collab)
            )
            runPermissionsTest(LibraryPermission.CREATE_SLACK_INTEGRATION)(
              data.main -> Set(data.owner),
              data.orgGeneral -> data.orgMembers,
              data.secret -> data.libMembers,
              data.orgVisible -> data.orgMembers,
              data.public -> data.allUsers
            )
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
      "add comment permissions" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val (orgOwner, libOwner, collab, follower, member, rando, noone, publicLib, orgLib, secretLib, openCollabLib, openCommentLib) = setupLibs()
            val all = Set(orgOwner, libOwner, collab, follower, member, rando, noone)

            val whoCanComment2 = all.filter { x => permissionCommander.getLibraryPermissions(openCollabLib.id.get, x).contains(LibraryPermission.ADD_COMMENTS) }
            whoCanComment2 === Set(orgOwner, libOwner, collab, member, follower) // all org members can comment

            val whoCanComment3 = all.filter { x => permissionCommander.getLibraryPermissions(openCommentLib.id.get, x).contains(LibraryPermission.ADD_COMMENTS) }
            whoCanComment3 === all // anyone can comment on a public library
          }
        }
      }
    }
  }
}
