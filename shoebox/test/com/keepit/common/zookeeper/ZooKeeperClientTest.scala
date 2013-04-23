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

  //args(skipAll = true)

  "zookeeper" should {
    "connect to server and create paths" in {
      val zk = new ZooKeeperClient("localhost", 2000, Path("/test"),
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.createPath(Path("/a/b/c"))
      zk.createPath(Path("/a/b/d"))
      val children = zk.getChildren(Path("/a/b"))
      children.toSet === Set(Node("c"), Node("d"))
      zk.set(Node("/a/b/c"), "foo".getBytes())
      val s = new String(zk.get(Node("/a/b/c")))
      s === "foo"
      zk.deleteRecursive(Path("/a"))
    }

    "connect to server and " in {
      val zk = new ZooKeeperClient("localhost", 2000, Path("/test"),
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.createNode(Node("/test-node"), "foo".getBytes, CreateMode.PERSISTENT)
      zk.watchNode(Node("/test-node"), { (data : Option[Array[Byte]]) =>
        data match {
          case Some(d) => println("Data updated: %s".format(new String(d)))
          case None => println("Node deleted")
        }
      })
      zk.set(Node("/test-node"), "bar".getBytes)
      zk.set(Node("/test-node"), "baz".getBytes)
      zk.deleteNode(Node("/test-node"))
    }

    "monitor node children" in {
      val zk = new ZooKeeperClient("localhost", 2000, Path("/test"),
                      Some({zk1 => println(s"in callback, got $zk1")}))
      zk.create(Path("/parent"), null, CreateMode.PERSISTENT)
      zk.watchChildren(Path("/parent"), { (children : Seq[Node]) =>
        println("Children: %s".format(children.mkString(", ")))
      })
      zk.createNode(Node("/parent/child1"), null, CreateMode.PERSISTENT)
      zk.createNode(Node("/parent/child2"), null, CreateMode.PERSISTENT)
      zk.deleteNode(Node("/parent/child1"))
      zk.createNode(Node("/parent/child3"), null, CreateMode.PERSISTENT)
      zk.deleteRecursive(Path("/parent"))
    }

    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      val zk = new ZooKeeperClient("localhost", 2000, Path("/test"),
                      Some({zk1 => println(s"in callback, got $zk1")}))
      val childMap = collection.mutable.Map[Node, String]()

      zk.create(Path("/parent"), null, CreateMode.PERSISTENT)
      zk.watchChildrenWithData(Path("/parent"), childMap, {data => new String(data)})

      zk.create(Path("/parent/a"), "foo".getBytes, CreateMode.PERSISTENT)
      zk.create(Path("/parent/b"), "bar".getBytes, CreateMode.PERSISTENT)
      println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

      zk.delete(Path("/parent/a"))
      zk.set(Node("/parent/b"), "bar2".getBytes)
      zk.createNode(Node("/parent/c"), "baz".getBytes, CreateMode.PERSISTENT)
      println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap
    }
  }
}
