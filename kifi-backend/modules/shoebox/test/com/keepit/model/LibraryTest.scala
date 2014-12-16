package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val u1 = User(firstName = "Aaron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test")
    val u2 = User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2), username = Username("test"), normalizedUsername = "test")

    db.readWrite { implicit s =>
      val user1 = userRepo.save(u1)
      val user2 = userRepo.save(u2)

      val l1 = libraryRepo.save(Library(name = "lib1A", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))

      val l2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))

      val l3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("C"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))

      val s1 = libraryRepo.save(Library(name = "Main Library", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("main"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))

      val s2 = libraryRepo.save(Library(name = "Secret Library", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_SECRET, slug = LibrarySlug("secret"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))

      (l1, l2, l3, s1, s2, user1, user2)
    }
  }

  "LibraryRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster(implicit session => libraryRepo.all)
        all.map(_.name) === Seq("lib1A", "lib1B", "lib2", Library.SYSTEM_MAIN_DISPLAY_NAME, Library.SYSTEM_SECRET_DISPLAY_NAME)
        all.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.DISCOVERABLE, LibraryVisibility.PUBLISHED, LibraryVisibility.DISCOVERABLE, LibraryVisibility.SECRET)
        all.map(_.slug.value) === Seq("A", "B", "C", "main", "secret")
      }
    }

    "find a user's libraries" in {
      withDb() { implicit injector =>
        val (l1, l2, l3, _, _, user1, user2) = setup()
        db.readOnlyMaster { implicit session =>
          val user1Lib = libraryRepo.getByUser(user1.id.get)
          user1Lib.length === 4
          user1Lib.head._1.access === LibraryAccess.OWNER
          user1Lib.head._2.id === l1.id
          val user2lib = libraryRepo.getByUser(user2.id.get)
          user2lib(0)._1.access === LibraryAccess.READ_ONLY
          user2lib(0)._2.id === l2.id
          user2lib(1)._1.access === LibraryAccess.OWNER
          user2lib(1)._2.id === l3.id
        }
      }
    }
    "validate library names" in {
      Library.isValidName("asdf1234") === true
      Library.isValidName("q@#$%^&*().,/][:;\"~`--___+= ") === false
      Library.isValidName("") === false
    }

    "validate library slugs" in {
      LibrarySlug.isValidSlug("asdf1234") === true
      LibrarySlug.isValidSlug("asdf+qwer") === true
      LibrarySlug.isValidSlug("asdf 1234") === false
      LibrarySlug.isValidSlug("") === false
    }

    "generate valid library slugs" in {
      val slug1 = LibrarySlug.generateFromName("-- Foo, Bar & Baz! --")
      slug1 === "foo-bar-baz"
      LibrarySlug.isValidSlug(slug1) === true
      val slug2 = LibrarySlug.generateFromName("A Super Long Library Name That Surely Never Would Be Actually Chosen")
      slug2 === "a-super-long-library-name-that-surely-never-would"
      LibrarySlug.isValidSlug(slug2) === true
    }

    "reflect latest display naming scheme" in {
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster { implicit session => libraryRepo.all() }
        val Some(r1) = all.collectFirst { case lib if lib.kind == LibraryKind.SYSTEM_MAIN => lib }
        println(s"r1=$r1")
        r1.kind === LibraryKind.SYSTEM_MAIN
        r1.name === Library.SYSTEM_MAIN_DISPLAY_NAME

        val Some(r2) = all.collectFirst { case lib if lib.kind == LibraryKind.SYSTEM_SECRET => lib }
        r2.kind === LibraryKind.SYSTEM_SECRET
        r2.name === Library.SYSTEM_SECRET_DISPLAY_NAME
      }
    }

  }
}
