package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._

class LibraryTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = UserFactory.user().withCreatedAt(t1).withName("Aaron", "H").withUsername("test").saved
      val user2 = UserFactory.user().withCreatedAt(t1.plusHours(2)).withName("Jackie", "Chan").withUsername("test2").saved
      val user3 = UserFactory.user().withCreatedAt(t1.plusHours(2)).withName("Hoe", "Bat").withUsername("test3").saved

      val l1 = libraryRepo.save(Library(name = "lib1A", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val l2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user3.id.get, access = LibraryAccess.READ_ONLY))

      val l3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("C"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))

      val s1 = libraryRepo.save(Library(name = Library.SYSTEM_MAIN_DISPLAY_NAME, ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("main"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      val s2 = libraryRepo.save(Library(name = Library.SYSTEM_SECRET_DISPLAY_NAME, ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, createdAt = t1.plusMinutes(1), kind = LibraryKind.SYSTEM_SECRET, slug = LibrarySlug("secret"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = s2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

      (l1, l2, l3, s1, s2, user1, user2, user3)
    }
  }

  "LibraryRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster(implicit session => libraryRepo.aTonOfRecords)
        all.map(_.name) === Seq("lib1A", "lib1B", "lib2", Library.SYSTEM_MAIN_DISPLAY_NAME, Library.SYSTEM_SECRET_DISPLAY_NAME)
        all.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.DISCOVERABLE, LibraryVisibility.PUBLISHED, LibraryVisibility.DISCOVERABLE, LibraryVisibility.SECRET)
        all.map(_.slug.value) === Seq("A", "B", "C", "main", "secret")
      }
    }

    "owner and follower" in {
      withDb() { implicit injector =>
        val (l1, l2, l3, s1, s2, user1, user2, user3) = setup()
        db.readOnlyMaster(implicit session => libraryRepo.getOwnerLibrariesUserFollows(user1.id.get, user2.id.get)) === Seq(l2)
        db.readOnlyMaster(implicit session => libraryRepo.getOwnerLibrariesUserFollows(user1.id.get, user3.id.get)) === Seq(l2)
        db.readOnlyMaster(implicit session => libraryRepo.getOwnerLibrariesUserFollows(user2.id.get, user1.id.get)) === Seq()
      }
    }

    "both follow" in {
      withDb() { implicit injector =>
        val (l1, l2, l3, s1, s2, user1, user2, user3) = setup()
        db.readOnlyMaster(implicit session => libraryRepo.getMutualLibrariesForUser(user3.id.get, user2.id.get, 0, 10)) === Seq(l2)
        db.readOnlyMaster(implicit session => libraryRepo.getMutualLibrariesForUser(user1.id.get, user2.id.get, 0, 10)) === Seq()
        db.readOnlyMaster(implicit session => libraryRepo.getMutualLibrariesForUser(user1.id.get, user3.id.get, 0, 10)) === Seq()
      }
    }

    "find a user's libraries" in {
      withDb() { implicit injector =>
        val (l1, l2, l3, _, _, user1, user2, _) = setup()
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
      Seq(
        "-- Foo's, Bar & Baz! --" -> "foos-bar-baz",
        "Far-away Places I’d   like to gó" -> "far-away-places-i’d-like-to-gó",
        "Gift Ideas -- For That Special Someone/Someones" -> "gift-ideas-for-that-special-someonesomeones",
        "A Super Long Library Name That Surely Never Would Be Actually Chosen" -> "a-super-long-library-name-that-surely-never-would",
        "מה נשמע" -> "מה-נשמע",
        "Connections" -> "connections-") map {
          case (name, slug) =>
            LibrarySlug.generateFromName(name) === slug
            slug -> LibrarySlug.isValidSlug(slug) === slug -> true
        } head
    }

    "reflect latest display naming scheme" in {
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster { implicit session => libraryRepo.aTonOfRecords() }
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
