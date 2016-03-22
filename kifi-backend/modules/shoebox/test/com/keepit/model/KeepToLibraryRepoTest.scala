package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.Specification

import scala.util.Random

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
  def createKeepsAtUri(uri: NormalizedURI, lib: Library, n: Int = 1)(implicit session: RWSession, injector: Injector) = {
    val mainKeep = KeepFactory.keep().withURIId(uri.id.get).withLibrary(lib).saved
    val nonprimaryKeeps = KeepFactory.keeps(n - 1).map(_.withURIId(uri.id.get).withLibrary(lib)).saved
    mainKeep +: nonprimaryKeeps
  }

  def randomLibs(n: Int, owner: User, orgId: Option[Id[Organization]] = None)(implicit injector: Injector, session: RWSession): Seq[Library] = {
    // Random libraries with random keeps at random times
    val libs = LibraryFactory.libraries(10).map(_.published().withOwner(owner).withOrganizationIdOpt(orgId)).saved
    libs.foreach { lib =>
      val numKeeps = 20 + Random.nextInt(50)
      KeepFactory.keeps(numKeeps).map(_.withUser(owner).withLibrary(lib).withKeptAt(currentDateTime.minusDays(Random.nextInt(1000)))).saved
    }
    libs
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

            val allKeeps = userKeeps ++ otherKeeps ++ deadOtherKeeps ++ randoKeeps
            val firstOrderImplicitKeeps = userKeeps.filter(_.uriId == uri.id.get) ++ otherKeeps.filter(_.uriId == uri.id.get)

            inject[KeepToLibraryRepo].count === allKeeps.length
            val actual = inject[KeepToLibraryRepo].getVisibileFirstOrderImplicitKeeps(Set(uri.id.get), Set(userLib, otherLib).map(_.id.get)).map(_.keepId)
            val expected = firstOrderImplicitKeeps.map(_.id.get).toSet
            actual === expected
          }
          1 === 1
        }
      }
    }
    "be backwards compatible with some KeepRepo methods" in {
      "match getByLibrary" in {
        //def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = randomLibs(10, owner = user)

            for (lib <- libs) {
              val expected = inject[KeepRepo].pageByLibrary(lib.id.get, offset = 10, limit = 20).map(_.id.get)
              val actual = inject[KeepToLibraryRepo].getByLibraryIdSorted(lib.id.get, offset = Offset(10), limit = Limit(20))
              actual === expected
            }
          }
          1 === 1
        }
      }
      "match librariesWithMostKeepsSince" in {
        // def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = randomLibs(10, user)

            val libIds = libs.map(_.id.get).toSet
            val expected = inject[KeepRepo].getMaxKeepSeqNumForLibraries(libIds)
            val actual = inject[KeepToLibraryRepo].getMaxKeepSeqNumForLibraries(libIds)

            expected === actual
          }
          1 === 1
        }
      }

      "getSortedByKeepCountSince compiles" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit s =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val libs = randomLibs(20, user)
            val orgLibs = randomLibs(20, user, Some(org.id.get))
            val libIds = inject[KeepToLibraryRepo].getSortedByKeepCountSince(user.id.get, None, currentDateTime.minusDays(14), Offset(0), Limit(10))
            val orgLibIds = inject[KeepToLibraryRepo].getSortedByKeepCountSince(user.id.get, Some(org.id.get), currentDateTime.minusDays(14), Offset(0), Limit(10))
            1 === 1
          }
        }
      }
    }
  }
}
