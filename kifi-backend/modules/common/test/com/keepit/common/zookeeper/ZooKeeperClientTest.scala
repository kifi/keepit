package com.keepit.common.zookeeper

import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}
import org.apache.zookeeper.KeeperException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ZooKeeperClientTest extends Specification {

//  args(skipAll = true)

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
        zk.deleteRecursive(Node(s"${node.path}/a"))
        1 === 1
      }
    }

    "connect to server and set some data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val testNode = zk.create(Node(s"${node.path}/testNode"), "foo".getBytes)
        zk.watchNode(testNode, { (data : Option[Array[Byte]]) =>
          data match {
            case Some(d) => println("Data updated: %s".format(new String(d)))
            case None => println("Node deleted")
          }
        })
        zk.setData(testNode, "bar".getBytes)
        new String(zk.getData(testNode)) === "bar"
        zk.setData(testNode, "baz".getBytes)
        new String(zk.getData(testNode)) === "baz"
        zk.delete(testNode)

        zk.getData(testNode) must throwA[KeeperException.NoNodeException]
      }
    }

    "monitor node children" in {
      println("monitoring")
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        @volatile var latch = new CountDownLatch(1)
        @volatile var childSet = Set.empty[Node]
        val parent = zk.create(Node(s"${node.path}/parent"), null)
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          childSet = children.toSet
          println("Children: %s".format(children.mkString(", ")))
          latch.countDown()
        })
        latch.await()
        childSet === Set()

        latch = new CountDownLatch(1)
        val child1 = zk.createChild(parent, "child1", null, PERSISTENT)
        latch.await()
        childSet === Set(Node(parent, "child1"))

        latch = new CountDownLatch(1)
        val child2 = zk.createChild(parent, "child2", null, PERSISTENT)
        latch.await()
        childSet === Set(Node(parent, "child1"), Node(parent, "child2"))

        latch = new CountDownLatch(1)
        zk.delete(child1)
        latch.await()
        childSet === Set(Node(parent, "child2"))

        latch = new CountDownLatch(1)
        val child3 = zk.createChild(parent, "child3", null, PERSISTENT)
        latch.await()
        childSet === Set(Node(parent, "child2"), Node(parent, "child3"))
      }
    }

    "SEQUENCE EPHEMERAL (Service Instances) nodes" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = Node(node, "parent")
        zk.create(parent, null)
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          println("Service Instances: %s".format(children.mkString(", ")))
        })
        println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createChild(parent, "child", null, EPHEMERAL_SEQUENTIAL))

        zk.getChildren(parent).size === 3

        val other = zk.create(Node(s"${node.path}/other"), null)
        println(zk.createChild(other, "child", null, PERSISTENT))
      }(node, false)
      withZKSession { zk =>
        zk.getChildren(Node(s"${node.path}/other")).size === 1
        zk.getChildren(Node(s"${node.path}/parent")).size === 0
      }(node, true)
    }

    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = Node(node, "parent")
        val childMap = collection.mutable.Map[Node, String]()

        zk.create(parent, null)
        zk.watchChildrenWithData(parent, childMap, { data => new String(data) })

        val child1 = zk.createChild(parent, "a", "foo".getBytes, PERSISTENT)
        val child2 = zk.createChild(parent, "b", "bar".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

        zk.delete(child1)
        zk.setData(child2, "bar2".getBytes)
        zk.createChild(parent, "c", "baz".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

        1 === 1
      }
    }
  }
}
