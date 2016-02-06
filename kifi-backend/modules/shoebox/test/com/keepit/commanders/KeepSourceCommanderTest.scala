package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.KeepFactory
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mutable.SpecificationLike

class KeepSourceCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val ctxt = HeimdalContext.empty
  val modules: Seq[ScalaModule] = Seq(
    FakeExecutionContextModule()
  )

  "KeepSourceCommander" should {
    "reattribute keeps" in {
      "from a slack author" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit s =>
            KeepFactory.keeps(100).
          }
        }
      }
    }
  }

}
