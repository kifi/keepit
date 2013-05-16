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
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}

class ZooKeeperClientTest extends Specification {

  args(skipAll = true)

  def withClient[T](block: ZooKeeperClient => T)(implicit path: Path = Path("/test" + Random.nextLong.abs), cleanup: Boolean = true): T = {
    println(s"starting test with root path $path")
    val zk = new ZooKeeperClientImpl("localhost", 2000, path, Some( {zk1 => println(s"in callback, got $zk1")} ))
    try {
      zk.createPath(path)
      block(zk)
    } finally {
      if (cleanup) Try { zk.deleteRecursive(path) }
      zk.close
    }
  }

  "zookeeper" should {
    "connect to server and create paths" in {
      withClient { zk =>
        zk.createPath(Path("/a/b/c"))
        zk.createPath(Path("/a/b/d"))
        val children = zk.getChildren(Path("/a/b"))
        children.toSet === Set(Node("c"), Node("d"))
        zk.set(Node("/a/b/c"), "foo".getBytes())
        val s = new String(zk.get(Node("/a/b/c")))
        s === "foo"
        zk.deleteRecursive(Path("/a"))
      }
    }

    "connect to server and set some data" in {
      withClient { zk =>
        zk.createNode(Node("/testNode"), "foo".getBytes, PERSISTENT)
        zk.watchNode(Node("/testNode"), { (data : Option[Array[Byte]]) =>
          data match {
            case Some(d) => println("Data updated: %s".format(new String(d)))
            case None => println("Node deleted")
          }
        })
        zk.set(Node("/testNode"), "bar".getBytes)
        zk.set(Node("/testNode"), "baz".getBytes)
        zk.deleteNode(Node("/testNode"))
      }
    }

    "monitor node children" in {
      withClient { zk =>
        zk.create(Path("/parent"), null, PERSISTENT)
        zk.watchChildren(Path("/parent"), { (children : Seq[Node]) =>
          println("Children: %s".format(children.mkString(", ")))
        })
        zk.createNode(Node("/parent/child1"), null, PERSISTENT)
        zk.createNode(Node("/parent/child2"), null, PERSISTENT)
        zk.deleteNode(Node("/parent/child1"))
        zk.createNode(Node("/parent/child3"), null, PERSISTENT)
        zk.deleteRecursive(Path("/parent"))
      }
    }

    "SEQUENCE EPHEMERAL (Service Instances) nodes" in {
      var basePath = Path("/test" + Random.nextLong.abs)
      withClient { zk =>
        basePath = zk.basePath
        val parent = Path("/parent")
        zk.create(parent, null, PERSISTENT)
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          println("Service Instances: %s".format(children.mkString(", ")))
        })
        println("new node: " + zk.createNode(Node("/parent/child1"), null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createNode(Node("/parent/child2"), null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createNode(Node("/parent/child3"), null, EPHEMERAL_SEQUENTIAL))
        zk.getChildren(parent).size === 3

        zk.create(Path("other"), null, PERSISTENT)
        println(zk.createNode(Node("/other/child"), null, PERSISTENT))
      }(basePath, false)
      withClient { zk =>
        zk.getChildren(Path("/other")).size === 1
        zk.getChildren(Path("/parent")).size === 0
      }(basePath, true)
    }

    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      withClient { zk =>
        val childMap = collection.mutable.Map[Node, String]()

        zk.create(Path("/parent"), null, PERSISTENT)
        zk.watchChildrenWithData(Path("/parent"), childMap, {data => new String(data)})

        zk.create(Path("/parent/a"), "foo".getBytes, PERSISTENT)
        zk.create(Path("/parent/b"), "bar".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

        zk.delete(Path("/parent/a"))
        zk.set(Node("/parent/b"), "bar2".getBytes)
        zk.createNode(Node("/parent/c"), "baz".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap
      }
    }
  }
}
