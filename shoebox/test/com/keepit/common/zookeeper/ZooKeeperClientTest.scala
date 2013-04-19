package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.common.db.slick._

class ZooKeeperClientTest extends Specification {

  "zookeeper" should {
    "connect to server" in {
      val zk = new ZooKeeperClient("localhost", 2000, "/discovery",
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.createPath("/a/b/c")
      zk.createPath("/a/b/d")
      val children = zk.getChildren("/a/b")
      children.toSet === Set("c", "d")
      zk.set("/a/b/c", "foo".getBytes())
      val s = new String(zk.get("/a/b/c")) // "foo"
      s === "foo"
      //zk.deleteRecursive("/a") // delete "a" and all children
    }
  }
}
