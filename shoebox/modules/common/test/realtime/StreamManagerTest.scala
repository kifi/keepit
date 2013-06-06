package com.keepit.realtime

import scala.concurrent._
import scala.language.implicitConversions
import org.specs2.mutable._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.test.EmptyApplication
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.test.Helpers._
import com.keepit.common.db._
import concurrent._
import concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import org.specs2.execute.SkipException

class StreamManagerTest extends SpecificationWithJUnit {

  "StreamManagerTest" should {
    "do something" in {
      // TODO(andrew): Fix this test. Needs to test the enumerator/channel.
      /*val streamManager = new StreamManager
      val enumerator = streamManager.connect(Id[User](1))
      val printlnIteratee = Iteratee.foreach[JsObject](s => println(s))
      val consume: Iteratee[JsObject,JsObject] = {
        Iteratee.fold[JsObject,JsObject](JsObject(Nil)) { (result, chunk) =>
          println(s"consuming $chunk")
          result ++ chunk
        }
      }

      streamManager.push(Id[User](1), Json.obj("test" -> "value"))

      val promise = (enumerator |>> Iteratee.fold[JsObject, JsObject](JsObject(Nil))(_ ++ _)).flatMap(_.run)
            streamManager.disconnect(Id[User](0))

      println(Await.result(promise, Duration(1, SECONDS)))

*/
      1===1
    }

  }
}
