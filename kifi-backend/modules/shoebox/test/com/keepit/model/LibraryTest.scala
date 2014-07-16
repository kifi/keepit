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
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("A")))
      val l2 = libraryRepo.save(Library(name = "lib1B", ownerId = user1.id.get, visibility = LibraryVisibility.LIMITED,
        createdAt = t1.plusMinutes(2), slug = LibrarySlug("B")))
      val l3 = libraryRepo.save(Library(name = "lib2", ownerId = user2.id.get, visibility = LibraryVisibility.ANYONE,
        createdAt = t1.plusMinutes(1), slug = LibrarySlug("C")))
      (l1, l2, l3, user1, user2)
    }
  }

  "LibraryRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster(implicit session => libraryRepo.all)
        all.map(_.name) === Seq("lib1A", "lib1B", "lib2")
        all.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.LIMITED, LibraryVisibility.ANYONE)
        all.map(_.slug.value) === Seq("A", "B", "C")
      }
    }
  }
}
