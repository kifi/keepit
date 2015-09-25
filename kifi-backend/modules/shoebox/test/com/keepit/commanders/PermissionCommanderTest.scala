package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
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
    "make sure library permissions are reported correctly" in {
      "for system libraries" in {
        withDb(modules: _*) { implicit injector =>
          val systemLibs = {
            val mainLib = LibraryFactory.library().withKind(LibraryKind.SYSTEM_MAIN).get
            val secretLib = LibraryFactory.library().withKind(LibraryKind.SYSTEM_SECRET).get
            val readItLaterLib = LibraryFactory.library().withKind(LibraryKind.SYSTEM_READ_IT_LATER).get
            Set(mainLib, secretLib, readItLaterLib)
          }
          for (lib <- systemLibs) {
            permissionCommander.libraryPermissionsByAccess(lib, LibraryAccess.OWNER) === Set(
              LibraryPermission.VIEW_LIBRARY,
              LibraryPermission.ADD_KEEPS,
              LibraryPermission.EDIT_OWN_KEEPS,
              LibraryPermission.REMOVE_OWN_KEEPS
            )
          }
          1 === 1
        }
      }
      "for user created libraries" in {
        withDb(modules: _*) { implicit injector =>
          val lib = LibraryFactory.library().withKind(LibraryKind.USER_CREATED).get
          permissionCommander.libraryPermissionsByAccess(lib, LibraryAccess.OWNER) === Set(
            LibraryPermission.VIEW_LIBRARY,
            LibraryPermission.EDIT_LIBRARY,
            LibraryPermission.MOVE_LIBRARY,
            LibraryPermission.DELETE_LIBRARY,
            LibraryPermission.REMOVE_MEMBERS,
            LibraryPermission.ADD_KEEPS,
            LibraryPermission.EDIT_OWN_KEEPS,
            LibraryPermission.REMOVE_OWN_KEEPS,
            LibraryPermission.REMOVE_OTHER_KEEPS,
            LibraryPermission.INVITE_FOLLOWERS,
            LibraryPermission.INVITE_COLLABORATORS
          )
        }
      }
    }
  }
}
