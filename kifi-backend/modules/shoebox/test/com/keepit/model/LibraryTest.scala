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
    val u1 = new User(firstName = "Aaron", lastName = "H", createdAt = t1)
    val u2 = new User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2))

    db.readWrite { implicit s =>
      val user1 = userRepo.save(u1)
      val user2 = userRepo.save(u2)
      val l1 = libraryRepo.save(Library(name = "lib1A", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))

      val l2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l2.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))

      val l3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.PUBLISHED,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("C"), memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = l3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))

      (l1, l2, l3, user1, user2)
    }
  }

  "LibraryRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster(implicit session => libraryRepo.all)
        all.map(_.name) === Seq("lib1A", "lib1B", "lib2")
        all.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.DISCOVERABLE, LibraryVisibility.PUBLISHED)
        all.map(_.slug.value) === Seq("A", "B", "C")
      }
    }

    "find a user's libraries" in {
      withDb() { implicit injector =>
        val (l1, l2, l3, user1, user2) = setup()
        db.readOnlyMaster { implicit session =>
          val user1Lib = libraryRepo.getByUser(user1.id.get)
          user1Lib.length === 2
          user1Lib.head._1 === LibraryAccess.OWNER
          user1Lib.head._2.id === l1.id
          val user2lib = libraryRepo.getByUser(user2.id.get)
          user2lib(0)._1 === LibraryAccess.READ_ONLY
          user2lib(0)._2.id === l2.id
          user2lib(1)._1 === LibraryAccess.OWNER
          user2lib(1)._2.id === l3.id
        }
      }
    }
    "validate library names" in {
      val name1 = "asdf1234"
      val name2 = "q@#$%^&*().,/][:;\"~`--___+= "
      val name3 = ""
      Library.isValidName(name1) === true
      Library.isValidName(name2) === false
      Library.isValidName(name3) === false
    }

    "validate library slugs" in {
      val str1 = "asdf1234"
      val str2 = "asdf+qwer"
      val str3 = "asdf 1234"
      val str4 = ""
      LibrarySlug.isValidSlug(str1) === true
      LibrarySlug.isValidSlug(str2) === true
      LibrarySlug.isValidSlug(str3) === false
      LibrarySlug.isValidSlug(str4) === false

      val slug1 = LibrarySlug(str1)
      slug1.value === str1
    }
  }
}
