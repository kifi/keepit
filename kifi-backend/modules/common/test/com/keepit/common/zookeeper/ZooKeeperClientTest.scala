package com.keepit.common.zookeeper

import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}

class ZooKeeperClientTest extends Specification {

  args(skipAll = true)

  def withZKSession[T](block: ZooKeeperSession => T)(implicit node: Node, cleanup: Boolean = true): T = {
    println(s"starting test with root path $node.path")
    val zkClient = new ZooKeeperClientImpl("localhost", 2000, Some( {zk1 => println(s"in callback, got $zk1")} ))
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
        zk.set(Node(s"${node.path}/a/b/c"), "foo".getBytes())
        val s = new String(zk.get(Node(s"${node.path}/a/b/c")))
        s === "foo"
        zk.deleteRecursive(Node(s"${node.path}/a"))
      }
    }

    "connect to server and set some data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        zk.create(Node(s"${node.path}/testNode"), "foo".getBytes, PERSISTENT)
        zk.watchNode(Node(s"${node.path}/testNode"), { (data : Option[Array[Byte]]) =>
          data match {
            case Some(d) => println("Data updated: %s".format(new String(d)))
            case None => println("Node deleted")
          }
        })
        zk.set(Node(s"${node.path}/testNode"), "bar".getBytes)
        zk.set(Node(s"${node.path}/testNode"), "baz".getBytes)
        zk.delete(Node(s"${node.path}/testNode"))
      }
    }

    "monitor node children" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        zk.create(Node(s"${node.path}/parent"), null, PERSISTENT)
        zk.watchChildren(Node(s"${node.path}/parent"), { (children : Seq[Node]) =>
          println("Children: %s".format(children.mkString(", ")))
        })
        zk.create(Node(s"${node.path}/parent/child1"), null, PERSISTENT)
        zk.create(Node(s"${node.path}/parent/child2"), null, PERSISTENT)
        zk.delete(Node(s"${node.path}/parent/child1"))
        zk.create(Node(s"${node.path}/parent/child3"), null, PERSISTENT)
        zk.deleteRecursive(Node(s"${node.path}/parent"))
      }
    }

    "SEQUENCE EPHEMERAL (Service Instances) nodes" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = Node(s"${node.path}/parent")
        zk.create(parent, null, PERSISTENT)
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          println("Service Instances: %s".format(children.mkString(", ")))
        })
        println("new node: " + zk.create(Node(s"${node.path}/parent/child1"), null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.create(Node(s"${node.path}/parent/child2"), null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.create(Node(s"${node.path}/parent/child3"), null, EPHEMERAL_SEQUENTIAL))
        zk.getChildren(parent).size === 3

        zk.create(Node(s"${node.path}/other"), null, PERSISTENT)
        println(zk.create(Node(s"${node.path}/other/child"), null, PERSISTENT))
      }(node, false)
      withZKSession { zk =>
        zk.getChildren(Node(s"${node.path}/other")).size === 1
        zk.getChildren(Node(s"${node.path}/parent")).size === 0
      }(node, true)
    }

    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val childMap = collection.mutable.Map[Node, String]()

        zk.create(Node(s"${node.path}/parent"), null, PERSISTENT)
        zk.watchChildrenWithData(Node(s"${node.path}/parent"), childMap, {data => new String(data)})

        zk.create(Node(s"${node.path}/parent/a"), "foo".getBytes, PERSISTENT)
        zk.create(Node(s"${node.path}/parent/b"), "bar".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

        zk.delete(Node(s"${node.path}/parent/a"))
        zk.set(Node(s"${node.path}/parent/b"), "bar2".getBytes)
        zk.create(Node(s"${node.path}/parent/c"), "baz".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap
      }
    }
  }
}
