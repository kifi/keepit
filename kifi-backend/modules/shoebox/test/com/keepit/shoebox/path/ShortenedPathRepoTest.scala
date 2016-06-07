package com.keepit.shoebox.path

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.path.Path
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class ShortenedPathRepoTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  def modules = Seq()

  "ShortenedPathRepo" should {
    "shorten a path" in {
      withDb(modules: _*) { implicit injector =>
        val p1 = Path("/brewstercorp/buffalo?longAssQueryParameter=obnoxiouslyLongValue")
        val sp = db.readWrite { implicit s => inject[ShortenedPathRepo].intern(p1) }
        val p2 = db.readOnlyMaster { implicit s => inject[ShortenedPathRepo].get(sp.id.get).path }
        p1.absolute === p2.absolute
      }
    }
    "intern properly" in {
      withDb(modules: _*) { implicit injector =>
        val p1 = Path("/brewstercorp/buffalo?longAssQueryParameter=obnoxiouslyLongValue")
        val p2 = Path("/brewstercorp/buffalo?longAssQueryParameter=someOtherValue")
        db.readWrite { implicit s =>
          inject[ShortenedPathRepo].intern(p1)
          inject[ShortenedPathRepo].intern(p1)
          inject[ShortenedPathRepo].count === 1
          inject[ShortenedPathRepo].intern(p2)
          inject[ShortenedPathRepo].count === 2
        }
      }
    }
  }
}
