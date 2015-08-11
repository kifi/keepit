package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._

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
    val nonprimaryKeeps = KeepFactory.keeps(n - 1).map(_.withURIId(uri.id.get).withLibrary(lib).nonPrimary()).saved
    mainKeep +: nonprimaryKeeps
  }

  def randomLibs(n: Int, owner: User)(implicit injector: Injector, session: RWSession): Seq[Library] = {
    val libs = LibraryFactory.libraries(10).map(_.withOwner(owner)).saved
    libs.foreach { lib =>
      val numKeeps = 20 + Random.nextInt(50)
      KeepFactory.keeps(numKeeps).map(_.withUser(owner).withLibrary(lib)).saved
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

            inject[KeepToLibraryRepo].count === userKeeps.length + otherKeeps.length + deadOtherKeeps.length + randoKeeps.length
            inject[KeepToLibraryRepo].getVisibileFirstOrderImplicitKeeps(user.id.get, uri.id.get) === (userKeeps.filter(_.uriId == uri.id.get) ++ otherKeeps.filter(_.uriId == uri.id.get)).map(_.id.get).toSet
          }
          1 === 1
        }
      }
    }
    "be backwards compatible with some KeepRepo methods" in {
      /*
      def recentKeepNotes(libId: Id[Library], limit: Int)(implicit session: RSession): Seq[String]
      def getByLibraryWithInconsistentOrgId(libraryId: Id[Library], expectedOrgId: Option[Id[Organization]], limit: Limit)(implicit session: RSession): Set[Id[Keep]]
      // These ones do not yet
      def getRecentKeepsFromFollowedLibraries(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]])(implicit session: RSession): Seq[Keep]
      // I think these ones need to be changed or rethought:
      def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
      def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]]
      def latestKeptAtByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[DateTime]]
      */
      "match getByLibrary" in {
        //def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = randomLibs(10, owner = user)

            for (lib <- libs) {
              val expected = inject[KeepRepo].getByLibrary(lib.id.get, offset = 10, limit = 20).map(_.id.get)
              val actual = inject[KeepToLibraryRepo].getByLibraryId(lib.id.get, offset = Offset(10), limit = Limit(20)).map(_.keepId)
              actual === expected
            }
          }
          1 === 1
        }
      }
      "match getCountsByLibrary" in {
        // def getCountsByLibrary(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Int]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = randomLibs(10, owner = user)

            val expected = inject[KeepRepo].getCountsByLibrary(libs.map(_.id.get).toSet)
            val actual = inject[KeepToLibraryRepo].getCountsByLibraryIds(libs.map(_.id.get).toSet)
            expected === actual
          }
          1 === 1
        }
      }
      "match getByUserIdAndLibraryId" in {
        // def getByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = randomLibs(10, owner = user)

            for (lib <- libs) {
              val expected = inject[KeepRepo].getByUserIdAndLibraryId(user.id.get, lib.id.get)
              val actual = inject[KeepToLibraryRepo].getByUserIdAndLibraryId(user.id.get, lib.id.get)
              expected.map(_.id.get) === actual.map(_.keepId)
            }
          }
          1 === 1
        }
      }
      "match getByLibraryIds" in {
        // def getByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = randomLibs(10, owner = user)

            val expected = inject[KeepRepo].getByLibraryIds(libs.map(_.id.get).toSet)
            val actual = inject[KeepToLibraryRepo].getAllByLibraryIds(libs.map(_.id.get).toSet)
            expected.map(_.id.get).toSet === actual.values.flatten.map(_.keepId).toSet
          }
          1 === 1
        }
      }
      "match getPrimaryByUriAndLibrary" in {
        // def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved

            val uris = createUris(10).toSeq
            uris.foreach { uri => createKeepsAtUri(uri, lib, n = 5 + Random.nextInt(5)) }

            for (uri <- uris) {
              val expected = inject[KeepRepo].getPrimaryByUriAndLibrary(uri.id.get, lib.id.get)
              val actual = inject[KeepToLibraryRepo].getPrimaryByUriAndLibrary(uri.id.get, lib.id.get)
              expected.map(_.id.get) === actual.map(_.keepId)
            }
          }
          1 === 1
        }
      }
      "match getByLibraryIdsAndUriIds" in {
        // def getByLibraryIdsAndUriIds(libraryIds: Set[Id[Library]], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val libs = LibraryFactory.libraries(10).map(_.withOwner(user)).saved

            val uris = libs.flatMap { lib =>
              val libUris = createUris(5 + Random.nextInt(5))
              libUris.foreach { uri => createKeepsAtUri(uri, lib, 1 + Random.nextInt(5)) }
              libUris
            }

            uris.length must beGreaterThan(49)

            for (i <- 1 to 10) {
              val uriIds = Random.shuffle(uris).take(uris.length / 2).map(_.id.get).toSet
              val libIds = Random.shuffle(libs).take(libs.length / 2).map(_.id.get).toSet

              val expected = inject[KeepRepo].getByLibraryIdsAndUriIds(libIds, uriIds)
              val actual = inject[KeepToLibraryRepo].getByLibraryIdsAndUriIds(libIds, uriIds)
              expected.map(_.id.get) === actual.map(_.keepId)
            }
          }
          1 === 1
        }
      }
      "match getKeepsFromLibrarySince" in {
        // def getKeepsFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val dates = (0 to 100).map(currentDateTime.minusDays)
            for (date <- dates) {
              KeepFactory.keeps(5).map(_.withLibrary(lib).withUser(user).withKeptAt(date)).saved
            }

            for (date <- dates) {
              val expected = inject[KeepRepo].getKeepsFromLibrarySince(date, lib.id.get, max = 50)
              val actual = inject[KeepToLibraryRepo].getFromLibrarySince(date, lib.id.get, max = 50)
              expected.map(_.id.get) === actual.map(_.keepId)
            }
          }
          1 === 1
        }
      }
      "match getKeepsFromLibrarySince" in {
        // def getByLibraryIdAndExcludingVisibility(libId: Id[Library], excludeVisibility: Option[LibraryVisibility], limit: Int)(implicit session: RSession): Seq[Keep]
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val dates = (0 to 100).map(currentDateTime.minusDays)
            for (date <- dates) {
              KeepFactory.keeps(5).map(_.withLibrary(lib).withUser(user).withKeptAt(date)).saved
            }

            for (date <- dates) {
              val expected = inject[KeepRepo].getKeepsFromLibrarySince(date, lib.id.get, max = 50)
              val actual = inject[KeepToLibraryRepo].getFromLibrarySince(date, lib.id.get, max = 50)
              expected.map(_.id.get) === actual.map(_.keepId)
            }
          }
          1 === 1
        }
      }
    }
  }
}
