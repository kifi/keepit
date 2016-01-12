package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.slick.{ DBContext, SafeDatabase }
import com.keepit.model.KeepFactory
import com.keepit.model.KeepFactoryHelper.KeepPersister
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class RPBExampleCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  val modules = Seq()
  "RPBExampleCommander" should {
    "let me do awesome things" in {
      withDb() { implicit injector =>
        implicit val thisCanTotallyBeAbused = DBContext.takeResponsibility("rpb")
        val rpb = inject[RPBExampleCommander]
        val sdb = inject[SafeDatabase]
        val keep = db.readWrite { implicit s => KeepFactory.keep().saved }

        // All fine
        rpb.openSession(keep.id.get) === keep
        rpb.deferSession(keep.id.get) === keep
        sdb.read { implicit s => rpb.getKeep(keep.id.get) } === keep

        // Not fine, but will compile because of `thisCanTotallyBeAbused`
        sdb.read { implicit s => rpb.openSession(keep.id.get) } === keep
      }
    }
  }
}
