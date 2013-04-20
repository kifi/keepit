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
    "monitor node children" in {
      val zk = new ZooKeeperClient("localhost", 2000, "/discovery",
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.create("/parent", null, CreateMode.PERSISTENT)
      zk.watchChildren("/parent", { (children : Seq[String]) =>
        println("Children: %s".format(children.mkString(", ")))
      })
      zk.create("/parent/child1", null, CreateMode.PERSISTENT)
      zk.create("/parent/child2", null, CreateMode.PERSISTENT)
      zk.delete("/parent/child1")
      zk.create("/parent/child3", null, CreateMode.PERSISTENT)
      zk.deleteRecursive("/parent")
    }
    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      val zk = new ZooKeeperClient("localhost", 2000, "/discovery",
                      Some({zk1 => println(s"in callback, got $zk1")}))
      val childMap = collection.mutable.Map[String, String]()

      zk.create("/parent", null, CreateMode.PERSISTENT)
      zk.watchChildrenWithData("/parent", childMap, {data => new String(data)})

      zk.create("/parent/a", "foo".getBytes, CreateMode.PERSISTENT)
      zk.create("/parent/b", "bar".getBytes, CreateMode.PERSISTENT)
      println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

      zk.delete("/parent/a")
      zk.set("/parent/b", "bar2".getBytes)
      zk.create("/parent/c", "baz".getBytes, CreateMode.PERSISTENT)
      println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

    }
  }
}
