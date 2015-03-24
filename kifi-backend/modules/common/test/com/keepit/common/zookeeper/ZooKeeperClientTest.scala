package com.keepit.common.zookeeper

import com.keepit.common.strings._
import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import org.apache.zookeeper.{ CreateMode, KeeperException }
import scala.util.{ Random, Try }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch

class ZooKeeperClientTest extends Specification {

  args(skipAll = true)

  def withZKSession[T](block: ZooKeeperSession => T)(implicit node: Node, cleanup: Boolean = true): T = {
    // println(s"starting test with root path ${node.path}") // can be removed?
    val zkClient = new ZooKeeperClientImpl("localhost", 20000, Some({ zk1 => println(s"in callback, got $zk1") }))
    zkClient.session { zk =>
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
        zk.setData(Node(s"${node.path}/a/b/c"), "foo")
        val s = zk.getData[String](Node(s"${node.path}/a/b/c")).get
        s === "foo"
      }
    }

    "connect to server and set some data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val testNode = zk.createChild(node, "testNode", "foo".getBytes)
        zk.watchNode(testNode, { (data: Option[Array[Byte]]) =>
          data match {
            case Some(d) => println("Data updated: %s".format(new String(d)))
            case None => println("Node deleted")
          }
        })

        zk.getData[String](testNode) === Some("foo")

        zk.setData(testNode, "bar")
        zk.getData[String](testNode) === Some("bar")

        zk.setData(testNode, "baz")
        zk.getData[String](testNode) === Some("baz")
        zk.delete(testNode)

        zk.getData[String](testNode) must throwA[KeeperException.NoNodeException]
      }
      withZKSession { zk =>
        val testNode = zk.createChild(node, "testNode", CreateMode.PERSISTENT)
        zk.getData[String](testNode) === None
      }
    }

    "monitor node children with data watch" in {
      // println("monitoring") // can be removed?
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        @volatile var latch: Option[CountDownLatch] = None
        def mkLatch = { latch = Some(new CountDownLatch(1)) }
        def awaitLatch = { latch.map(_.await) }
        val updateCount = new AtomicInteger(0)

        @volatile var childMap = Map.empty[Node, String]
        val parent = zk.createChild(node, "parent", CreateMode.PERSISTENT)
        mkLatch
        zk.watchChildrenWithData[String](parent, { (children: Seq[(Node, String)]) =>
          childMap = children.toMap
          updateCount.incrementAndGet()
          // println(s"""#${updateCount.get} Children: ${children.mkString(", ")}""") // can be removed?
          latch.map(l => l.countDown())
        })
        awaitLatch
        childMap === Map()
        updateCount.get === 1

        mkLatch
        val child1 = zk.createChild(parent, "child1", CreateMode.PERSISTENT)
        awaitLatch
        childMap === Map(Node(parent, "child1") -> "")
        updateCount.get === 2

        mkLatch
        val child2 = zk.createChild(parent, "child2", CreateMode.PERSISTENT)
        awaitLatch
        childMap === Map(Node(parent, "child1") -> "", Node(parent, "child2") -> "")
        updateCount.get === 3

        mkLatch
        zk.setData[String](child2, "test")
        awaitLatch
        childMap === Map(Node(parent, "child1") -> "", Node(parent, "child2") -> "test")
        updateCount.get === 4

        mkLatch
        zk.deleteData(child2)
        awaitLatch
        childMap === Map(Node(parent, "child1") -> "", Node(parent, "child2") -> "")
        updateCount.get === 5

        mkLatch
        zk.delete(child1)
        awaitLatch
        childMap === Map(Node(parent, "child2") -> "")
        updateCount.get === 6

        mkLatch
        val child3 = zk.createChild(parent, "child3", "new node", CreateMode.PERSISTENT)
        awaitLatch
        childMap === Map(Node(parent, "child2") -> "", Node(parent, "child3") -> "new node")
        updateCount.get === 7

        mkLatch
        zk.deleteRecursive(parent)
        awaitLatch
        childMap === Map()
        updateCount.get === 10
      }
    }

    "monitor node children without data watch" in {
      // println("monitoring") // can be removed?
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        @volatile var latch: Option[CountDownLatch] = None
        def mkLatch = { latch = Some(new CountDownLatch(1)) }
        def awaitLatch = { latch.map(_.await) }
        val updateCount = new AtomicInteger(0)

        @volatile var childSet = Set.empty[Node]
        val parent = zk.createChild(node, "parent", CreateMode.PERSISTENT)
        mkLatch
        zk.watchChildren(parent, { (children: Seq[Node]) =>
          childSet = children.toSet
          updateCount.incrementAndGet()
          // println(s"""#${updateCount.get} Children: ${children.mkString(", ")}""") // can be removed?
          latch.map(l => l.countDown())
        })
        awaitLatch
        childSet === Set()
        updateCount.get === 1

        mkLatch
        val child1 = zk.createChild(parent, "child1", CreateMode.PERSISTENT)
        awaitLatch
        childSet === Set(Node(parent, "child1"))
        updateCount.get === 2

        mkLatch
        val child2 = zk.createChild(parent, "child2", CreateMode.PERSISTENT)
        awaitLatch
        childSet === Set(Node(parent, "child1"), Node(parent, "child2"))
        updateCount.get === 3

        zk.setData[String](child2, "test")
        mkLatch
        zk.delete(child1)
        awaitLatch
        childSet === Set(Node(parent, "child2"))
        updateCount.get === 4

        zk.deleteData(child2)
        mkLatch
        val child3 = zk.createChild(parent, "child3", CreateMode.PERSISTENT)
        awaitLatch
        childSet === Set(Node(parent, "child2"), Node(parent, "child3"))
        updateCount.get === 5

        mkLatch
        zk.deleteRecursive(parent)
        awaitLatch
        childSet === Set()
        updateCount.get === 8
      }
    }

    "SEQUENCE EPHEMERAL (Service Instances) nodes" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = zk.createChild(node, "parent", CreateMode.PERSISTENT)
        zk.watchChildrenWithData[String](parent, { (children: Seq[(Node, String)]) =>
          // println("Service Instances: %s".format(children.mkString(", "))) // can be removed?
        })
        // println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL)) // can be removed?
        // println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL)) // can be removed?
        // println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL)) // can be removed?

        zk.getChildren(parent).size === 3

        val other = zk.createChild(node, "other", CreateMode.PERSISTENT)
        // println(zk.createChild(other, "child")) // can be removed?
      }(node, false)
      withZKSession { zk =>
        zk.getChildren(Node(s"${node.path}/other")).size === 1
        zk.getChildren(Node(s"${node.path}/parent")).size === 0
      }(node, true)
    }
  }
}
