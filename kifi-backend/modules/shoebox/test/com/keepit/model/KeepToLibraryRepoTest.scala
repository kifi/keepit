package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._

class KeepToLibraryRepoTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeSocialGraphModule()
  )

  def createUri(title: String, url: String)(implicit session: RWSession, injector: Injector) = {
    uriRepo.save(NormalizedURI.withHash(title = Some(title), normalizedUrl = url))
  }
  def createUris(n: Int)(implicit session: RWSession, injector: Injector) = {
    for (i <- 1 to n) yield {
      val str = "http://www." + RandomStringUtils.randomAlphanumeric(20) + ".com"
      createUri(str, str)
    }
  }

  "KeepToLibraryRepo" should {

    "find keeps by uri" in {
      "find first-order implicit keeps" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit rw =>
            val user = UserFactory.user().saved
            val otherUser = UserFactory.user().saved

            val userLib = LibraryFactory.library().withOwner(user).saved
            val otherLib = LibraryFactory.library().withOwner(otherUser).withFollowers(Seq(user)).saved
            val deadOtherLib = LibraryFactory.library().withOwner(otherUser).withFollowers(Seq(user)).saved
            val deadOtherLibMembership = inject[LibraryMembershipRepo].getWithLibraryIdAndUserId(deadOtherLib.id.get, user.id.get).get
            inject[LibraryMembershipRepo].save(deadOtherLibMembership.withState(LibraryMembershipStates.INACTIVE))
            val randoLib = LibraryFactory.library().withOwner(otherUser).saved

            val uris = createUris(50).toSeq
            val uri = uris.head

            val userKeeps = uris.map(uri => KeepFactory.keep().withURIId(uri.id.get).withLibrary(userLib).saved)
            val otherKeeps = uris.map(uri => KeepFactory.keep().withURIId(uri.id.get).withLibrary(otherLib).saved)
            val deadOtherKeeps = uris.map(uri => KeepFactory.keep().withURIId(uri.id.get).withLibrary(deadOtherLib).saved)
            val randoKeeps = uris.map(uri => KeepFactory.keep().withURIId(uri.id.get).withLibrary(randoLib).saved)

            inject[KeepToLibraryRepo].count === userKeeps.length + otherKeeps.length + deadOtherKeeps.length + randoKeeps.length
            inject[KeepToLibraryRepo].getVisibileFirstOrderImplicitKeeps(user.id.get, uri.id.get) === (userKeeps.filter(_.uriId == uri.id.get) ++ otherKeeps.filter(_.uriId == uri.id.get)).map(_.id.get).toSet
          }
          1 === 1
        }
      }
    }
  }
}
