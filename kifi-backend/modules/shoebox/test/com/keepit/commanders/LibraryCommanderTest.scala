package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.crypto.{ PublicIdConfiguration, TestCryptoModule }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryCommanderTest extends Specification with ShoeboxTestInjector {

  def modules = FakeScrapeSchedulerModule() :: FakeSearchServiceClientModule() :: Nil

  def setupUsers()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      val userIron = userRepo.save(User(firstName = "Tony", lastName = "Stark", createdAt = t1))
      val userCaptain = userRepo.save(User(firstName = "Steve", lastName = "Rogers", createdAt = t1))
      val userAgent = userRepo.save(User(firstName = "Nick", lastName = "Fury", createdAt = t1))
      val userHulk = userRepo.save(User(firstName = "Bruce", lastName = "Banner", createdAt = t1))
      (userIron, userCaptain, userAgent, userHulk)
    }
  }

  def setupLibraries(userIron: User, userCaptain: User, userAgent: User, userHulk: User)(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 8, 1, 1, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      val libShield = libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
        visibility = LibraryVisibility.SECRET, ownerId = userAgent.id.get))
      val libMurica = libraryRepo.save(Library(name = "MURICA", slug = LibrarySlug("murica"),
        visibility = LibraryVisibility.ANYONE, ownerId = userAgent.id.get))
      val libScience = libraryRepo.save(Library(name = "Science & Stuff", slug = LibrarySlug("science"),
        visibility = LibraryVisibility.LIMITED, ownerId = userAgent.id.get))

      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userAgent.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libScience.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER))

      (libShield, libMurica, libScience)
    }
  }

  "LibraryCommander" should {
    "create libraries, memberships & invites" in {
      withDb(TestCryptoModule()) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk) = setupUsers()

        db.readOnlyMaster { implicit s =>
          libraryRepo.count === 0
        }

        val noInvites = Seq.empty[ExternalId[User]]
        val inv2: Seq[ExternalId[User]] = userIron.externalId :: userAgent.externalId :: userHulk.externalId :: Nil
        val inv3: Seq[ExternalId[User]] = userHulk.externalId :: Nil

        val lib1Request = LibraryAddRequest(name = "Avengers Missions", slug = "avengers",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites)

        val lib2Request = LibraryAddRequest(name = "MURICA", slug = "murica",
          visibility = LibraryVisibility.ANYONE, collaborators = noInvites, followers = inv2)

        val lib3Request = LibraryAddRequest(name = "Science and Stuff", slug = "science",
          visibility = LibraryVisibility.LIMITED, collaborators = inv3, followers = noInvites)

        val lib4Request = LibraryAddRequest(name = "Overlapped Invitees", slug = "overlap",
          visibility = LibraryVisibility.LIMITED, collaborators = inv2, followers = inv3)

        val lib5Request = LibraryAddRequest(name = "Invalid Param", slug = "",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites)

        val libraryCommander = inject[LibraryCommander]
        libraryCommander.addLibrary(lib1Request, userAgent.id.get).isRight === true
        libraryCommander.addLibrary(lib2Request, userCaptain.id.get).isRight === true
        libraryCommander.addLibrary(lib3Request, userIron.id.get).isRight === true
        libraryCommander.addLibrary(lib4Request, userIron.id.get).isRight === false
        libraryCommander.addLibrary(lib5Request, userIron.id.get).isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")

          val allMemberships = libraryMembershipRepo.all
          allMemberships.length === 3
          allMemberships.map(_.userId) === Seq(userAgent.id.get, userCaptain.id.get, userIron.id.get)
          allMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.OWNER, LibraryAccess.OWNER)

          val allInvites = libraryInviteRepo.all
          allInvites.length === 4
          val invitePairs = for (i <- allInvites) yield (i.ownerId, i.userId)

          invitePairs === (userCaptain.id.get, userIron.id.get) ::
            (userCaptain.id.get, userAgent.id.get) ::
            (userCaptain.id.get, userHulk.id.get) ::
            (userIron.id.get, userHulk.id.get) ::
            Nil
        }
      }

    }

    "modify library" in {
      withDb(TestCryptoModule()) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk) = setupUsers()
        val (libShield, libMurica, libScience) = setupLibraries(userIron, userCaptain, userAgent, userHulk)

        implicit val config = inject[PublicIdConfiguration]

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
          allLibs.map(_.description) === Seq(None, None, None)
          allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.ANYONE, LibraryVisibility.LIMITED)
        }

        val libraryCommander = inject[LibraryCommander]
        val mod1 = libraryCommander.modifyLibrary(libraryId = Library.publicId(libShield.id.get).get, userId = userAgent.externalId,
          description = Some("Samuel L. Jackson was here"))
        mod1.isRight === true
        val mod2 = libraryCommander.modifyLibrary(libraryId = Library.publicId(libMurica.id.get).get, userId = userCaptain.externalId,
          name = Some("MURICA #1!!!!!"), slug = Some("murica_#1"))
        mod2.isRight === true
        val mod3 = libraryCommander.modifyLibrary(libraryId = Library.publicId(libScience.id.get).get, userId = userIron.externalId,
          visibility = Some(LibraryVisibility.ANYONE))
        mod3.isRight === true
        val mod4 = libraryCommander.modifyLibrary(libraryId = Library.publicId(libScience.id.get).get, userId = userHulk.externalId,
          name = Some("HULK SMASH"))
        mod4.isRight === false
        val mod5 = libraryCommander.modifyLibrary(libraryId = Library.publicId(libScience.id.get).get, userId = userIron.externalId,
          name = Some(""))
        mod5.isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA #1!!!!!", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica_#1", "science")
          allLibs.map(_.description) === Seq(Some("Samuel L. Jackson was here"), None, None)
          allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.ANYONE, LibraryVisibility.ANYONE)
        }
      }
    }
  }
}
