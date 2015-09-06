package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

class LibraryInfoCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
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

  def randomEmails(n: Int): Seq[EmailAddress] = {
    (for (i <- 1 to 20) yield {
      RandomStringUtils.randomAlphabetic(15) + "@" + RandomStringUtils.randomAlphabetic(5) + ".com"
    }).toSeq.map(EmailAddress(_))
  }
  // Fill the system with a bunch of garbage
  def fillWithGarbage()(implicit injector: Injector, session: RWSession): Unit = {
    val n = 2
    for (i <- 1 to n) {
      val orgOwner = UserFactory.user().saved
      val libOwners = UserFactory.users(n).saved
      val collaborators = UserFactory.users(20).saved
      val followers = UserFactory.users(20).saved
      val invitedUsers = UserFactory.users(20).saved
      val invitedEmails = randomEmails(20)
      val org = OrganizationFactory.organization().withOwner(orgOwner).withMembers(libOwners ++ collaborators).withInvitedUsers(followers).saved
      for (lo <- libOwners) {
        LibraryFactory.library().withOwner(lo).withCollaborators(collaborators).withFollowers(followers).withInvitedUsers(invitedUsers).withInvitedEmails(invitedEmails).saved
        LibraryFactory.library().withOwner(lo).withCollaborators(collaborators).withFollowers(followers).withInvitedUsers(invitedUsers).withInvitedEmails(invitedEmails).withOrganization(org).saved
      }
    }
  }

  "LibraryInfoCommander" should {
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
            lib.permissionsByAccess(LibraryAccess.OWNER) === Set(
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
          lib.permissionsByAccess(LibraryAccess.OWNER) === Set(
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
