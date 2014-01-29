package com.keepit.common.zookeeper

import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}
import org.apache.zookeeper.KeeperException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ZooKeeperClientTest extends Specification {

  args(skipAll = true)

  def withZKSession[T](block: ZooKeeperSession => T)(implicit node: Node, cleanup: Boolean = true): T = {
    println(s"starting test with root path ${node.path}")
    val zkClient = new ZooKeeperClientImpl("localhost", 20000, Some( {zk1 => println(s"in callback, got $zk1")} ))
    zkClient.session{ zk =>
      try {
        zk.create(node)
        block(zk)
      } finally {
        if (cleanup) Try { zk.deleteRecursive(node) }
        zkClient.close()
      }
    }
  }

  "zookeeper" should {
    "connect to server and create paths" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        zk.create(Node(s"${node.path}/a/b/c"))
        zk.create(Node(s"${node.path}/a/b/d"))
        val children = zk.getChildren(Node(s"${node.path}/a/b"))
        children.toSet === Set(Node(s"${node.path}/a/b/c"), Node(s"${node.path}/a/b/d"))
        zk.setData(Node(s"${node.path}/a/b/c"), "foo".getBytes())
        val s = new String(zk.getData(Node(s"${node.path}/a/b/c")))
        s === "foo"
      }
    }

    "connect to server and set some data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val testNode = zk.createChild(node, "testNode", "foo".getBytes)
        zk.watchNode(testNode, { (data : Option[Array[Byte]]) =>
          data match {
            case Some(d) => println("Data updated: %s".format(new String(d)))
            case None => println("Node deleted")
          }
        })

        new String(zk.getData(testNode)) === "foo"

        zk.setData(testNode, "bar".getBytes)
        new String(zk.getData(testNode)) === "bar"

        zk.setData(testNode, "baz".getBytes)
        new String(zk.getData(testNode)) === "baz"
        zk.delete(testNode)

        zk.getData(testNode) must throwA[KeeperException.NoNodeException]

        zk.createChild(node, "testNode")
        zk.getData(testNode) === null
      }
    }

    "monitor node children" in {
      println("monitoring")
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        @volatile var latch: Option[CountDownLatch] = None
        def mkLatch = { latch = Some(new CountDownLatch(1)) }
        def awaitLatch = { latch.map(_.await) }

        @volatile var childSet = Set.empty[Node]
        val parent = zk.createChild(node, "parent")
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          childSet = children.toSet
          println("Children: %s".format(children.mkString(", ")))
          latch.map(l => l.countDown())
        })

        mkLatch
        val child1 = zk.createChild(parent, "child1")
        awaitLatch
        childSet === Set(Node(parent, "child1"))

        mkLatch
        val child2 = zk.createChild(parent, "child2")
        awaitLatch
        childSet === Set(Node(parent, "child1"), Node(parent, "child2"))

        mkLatch
        zk.delete(child1)
        awaitLatch
        childSet === Set(Node(parent, "child2"))

        mkLatch
        val child3 = zk.createChild(parent, "child3")
        awaitLatch
        childSet === Set(Node(parent, "child2"), Node(parent, "child3"))

        mkLatch
        zk.deleteRecursive(parent)
        awaitLatch
        childSet === Set()
      }
    }

    "SEQUENCE EPHEMERAL (Service Instances) nodes" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = zk.createChild(node, "parent")
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          println("Service Instances: %s".format(children.mkString(", ")))
        })
        println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL))

        zk.getChildren(parent).size === 3

        val other = zk.createChild(node, "other")
        println(zk.createChild(other, "child"))
      }(node, false)
      withZKSession { zk =>
        zk.getChildren(Node(s"${node.path}/other")).size === 1
        zk.getChildren(Node(s"${node.path}/parent")).size === 0
      }(node, true)
    }

    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = zk.createChild(node, "parent")
        val childMap = collection.mutable.Map[Node, String]()
        @volatile var latch: Option[CountDownLatch] = None
        def mkLatch = { latch = Some(new CountDownLatch(1)) }
        def awaitLatch = { latch.map(_.await) }

        zk.watchChildrenWithData(parent, childMap, { data => new String(data) }, { _ => latch.map(l => l.countDown()) })
        mkLatch
        val child1 = zk.createChild(parent, "a", "foo".getBytes)
        awaitLatch
        childMap === Map(Node(parent, "a") -> "foo")

        mkLatch
        val child2 = zk.createChild(parent, "b", "bar".getBytes)
        awaitLatch
        childMap === Map(Node(parent, "a") -> "foo", Node(parent, "b") -> "bar")

        mkLatch
        zk.delete(child1)
        awaitLatch
        childMap === Map(Node(parent, "b") -> "bar")

        mkLatch
        zk.setData(child2, "bar2".getBytes)
        awaitLatch
        childMap === Map(Node(parent, "b") -> "bar2")

        mkLatch
        zk.createChild(parent, "c", "baz".getBytes)
        awaitLatch
        childMap === Map(Node(parent, "b") -> "bar2", Node(parent, "c") -> "baz")
      }
    }
  }
}
