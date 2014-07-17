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

        val lib1Request = LibraryAddRequest(name = Some("Avengers Missions"), slug = Some("avengers"),
          visibility = Some(LibraryVisibility.SECRET), collaborators = noInvites, followers = noInvites)

        val lib2Request = LibraryAddRequest(name = Some("MURICA"), slug = Some("murica"),
          visibility = Some(LibraryVisibility.ANYONE), collaborators = noInvites, followers = inv2)

        val lib3Request = LibraryAddRequest(name = Some("Science and Stuff"), slug = Some("science"),
          visibility = Some(LibraryVisibility.LIMITED), collaborators = inv3, followers = noInvites)

        val lib4Request = LibraryAddRequest(name = Some("Overlapped Invitees"), slug = Some("overlap"),
          visibility = Some(LibraryVisibility.LIMITED), collaborators = inv2, followers = inv3)

        val lib5Request = LibraryAddRequest(name = Some("Invalid Param"), slug = Some(""),
          visibility = Some(LibraryVisibility.SECRET), collaborators = noInvites, followers = noInvites)

        val libraryCommander = inject[LibraryCommander]
        val add1 = libraryCommander.addLibrary(lib1Request, userAgent.id.get)
        add1.isRight === true
        val add2 = libraryCommander.addLibrary(lib2Request, userCaptain.id.get)
        add2.isRight === true
        val add3 = libraryCommander.addLibrary(lib3Request, userIron.id.get)
        add3.isRight === true
        val add4 = libraryCommander.addLibrary(lib4Request, userIron.id.get)
        add4.isRight === false
        val add5 = libraryCommander.addLibrary(lib5Request, userIron.id.get)
        add5.isRight === false

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
        }

        val libraryCommander = inject[LibraryCommander]
        val modifyReq1 = LibraryAddRequest(name = None, slug = None, description = Some("Samuel L. Jackson was here"),
          visibility = None, collaborators = Seq.empty, followers = Seq.empty)
        val modifyReq2 = LibraryAddRequest(name = Some("MURICA #1!!!!!"), slug = Some("murica_#1"), description = None,
          visibility = None, collaborators = Seq.empty, followers = Seq.empty)
        val modifyReq3 = LibraryAddRequest(name = None, slug = None, description = None,
          visibility = Some(LibraryVisibility.ANYONE), collaborators = Seq.empty, followers = Seq.empty)
        val badReq4 = LibraryAddRequest(name = Some("HULK SMASH"), slug = None, description = None,
          visibility = None, collaborators = Seq.empty, followers = Seq.empty)
        val badReq5 = LibraryAddRequest(name = Some(""), slug = None, description = None,
          visibility = None, collaborators = Seq.empty, followers = Seq.empty)

        libraryCommander.modifyLibrary(libraryId = libShield.publicId.get, libInfo = modifyReq1, userId = userAgent.externalId).isRight === true
        libraryCommander.modifyLibrary(libraryId = libMurica.publicId.get, libInfo = modifyReq2, userId = userCaptain.externalId).isRight === true
        libraryCommander.modifyLibrary(libraryId = libScience.publicId.get, libInfo = modifyReq3, userId = userIron.externalId).isRight === true
        libraryCommander.modifyLibrary(libraryId = libScience.publicId.get, libInfo = badReq4, userId = userHulk.externalId).isRight === false
        libraryCommander.modifyLibrary(libraryId = libScience.publicId.get, libInfo = badReq5, userId = userIron.externalId).isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA #1!!!!!", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica_#1", "science")
          allLibs.map(_.description) === Seq(Some("Samuel L. Jackson was here"), None, None)
        }
      }
    }
  }
}
