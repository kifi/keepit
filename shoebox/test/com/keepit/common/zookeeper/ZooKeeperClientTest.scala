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
import org.apache.zookeeper.CreateMode

class ZooKeeperClientTest extends Specification {

  args(skipAll = true)

  "zookeeper" should {
    "connect to server and create paths" in {
      val zk = new ZooKeeperClient("localhost", 2000, "/discovery",
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.createPath("/a/b/c")
      zk.createPath("/a/b/d")
      val children = zk.getChildren("/a/b")
      children.toSet === Set("c", "d")
      zk.set("/a/b/c", "foo".getBytes())
      val s = new String(zk.get("/a/b/c"))
      s === "foo"
      zk.deleteRecursive("/a")
    }
    "connect to server and " in {
      val zk = new ZooKeeperClient("localhost", 2000, "/discovery",
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.create("/test-node", "foo".getBytes, CreateMode.PERSISTENT)
      zk.watchNode("/test-node", { (data : Option[Array[Byte]]) =>
        data match {
          case Some(d) => println("Data updated: %s".format(new String(d)))
          case None => println("Node deleted")
        }
      })
      zk.set("/test-node", "bar".getBytes)
      zk.set("/test-node", "baz".getBytes)
      zk.delete("/test-node")
    }
  }
}
