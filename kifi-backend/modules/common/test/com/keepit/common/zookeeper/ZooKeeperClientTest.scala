package com.keepit.common.zookeeper

import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}

class ZooKeeperClientTest extends Specification {

  args(skipAll = true)

  def withZKSession[T](block: ZooKeeperSession => T)(implicit path: Path, cleanup: Boolean = true): T = {
    println(s"starting test with root path $path")
    val zkClient = new ZooKeeperClientImpl("localhost", 2000, Some( {zk1 => println(s"in callback, got $zk1")} ))
    zkClient.session{ zk =>
      try {
        zk.createPath(path)
        block(zk)
      } finally {
        if (cleanup) Try { zk.deleteRecursive(path) }
        zkClient.close()
      }
    }
  }

  "zookeeper" should {
    "connect to server and create paths" in {
      implicit val path = Path("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        zk.createPath(Path(s"{path.name}/a/b/c"))
        zk.createPath(Path(s"{path.name}/a/b/d"))
        val children = zk.getChildren(Path("/a/b"))
        children.toSet === Set(Node("c"), Node("d"))
        zk.set(Node(s"{path.name}/a/b/c"), "foo".getBytes())
        val s = new String(zk.get(Node(s"{path.name}/a/b/c")))
        s === "foo"
        zk.deleteRecursive(Path(s"{path.name}/a"))
        1 === 1
      }
    }

    "connect to server and set some data" in {
      implicit val path = Path("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        zk.createNode(Node(s"{path.name}/testNode"), "foo".getBytes, PERSISTENT)
        zk.watchNode(Node(s"{path.name}/testNode"), { (data : Option[Array[Byte]]) =>
          data match {
            case Some(d) => println("Data updated: %s".format(new String(d)))
            case None => println("Node deleted")
          }
        })
        zk.set(Node(s"{path.name}/testNode"), "bar".getBytes)
        zk.set(Node(s"{path.name}/testNode"), "baz".getBytes)
        zk.deleteNode(Node(s"{path.name}/testNode"))
        1 === 1
      }
    }

    "monitor node children" in {
      implicit val path = Path("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        zk.create(Path(s"{path.name}/parent"), null, PERSISTENT)
        zk.watchChildren(Path(s"{path.name}/parent"), { (children : Seq[Node]) =>
          println("Children: %s".format(children.mkString(", ")))
        })
        zk.createNode(Node(s"{path.name}/parent/child1"), null, PERSISTENT)
        zk.createNode(Node(s"{path.name}/parent/child2"), null, PERSISTENT)
        zk.deleteNode(Node(s"{path.name}/parent/child1"))
        zk.createNode(Node(s"{path.name}/parent/child3"), null, PERSISTENT)
        zk.deleteRecursive(Path(s"{path.name}/parent"))
        1 === 1
      }
    }

    "SEQUENCE EPHEMERAL (Service Instances) nodes" in {
      implicit val path = Path("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val parent = Path("/parent")
        zk.create(parent, null, PERSISTENT)
        zk.watchChildren(parent, { (children : Seq[Node]) =>
          println("Service Instances: %s".format(children.mkString(", ")))
        })
        println("new node: " + zk.createNode(Node(s"{path.name}/parent/child1"), null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createNode(Node(s"{path.name}/parent/child2"), null, EPHEMERAL_SEQUENTIAL))
        println("new node: " + zk.createNode(Node(s"{path.name}/parent/child3"), null, EPHEMERAL_SEQUENTIAL))
        zk.getChildren(parent).size === 3

        zk.create(Path(s"{path.name}/other"), null, PERSISTENT)
        println(zk.createNode(Node(s"{path.name}/other/child"), null, PERSISTENT))
      }(path, false)
      withZKSession { zk =>
        zk.getChildren(Path(s"{path.name}/other")).size === 1
        zk.getChildren(Path(s"{path.name}/parent")).size === 0
      }(path, true)

      1 === 1
    }

    "For a given node, automatically maintain a map from the node's children to the each child's data" in {
      implicit val path = Path("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val childMap = collection.mutable.Map[Node, String]()

        zk.create(Path(s"{path.name}/parent"), null, PERSISTENT)
        zk.watchChildrenWithData(Path(s"{path.name}/parent"), childMap, {data => new String(data)})

        zk.create(Path(s"{path.name}/parent/a"), "foo".getBytes, PERSISTENT)
        zk.create(Path(s"{path.name}/parent/b"), "bar".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

        zk.delete(Path(s"{path.name}/parent/a"))
        zk.set(Node(s"{path.name}/parent/b"), "bar2".getBytes)
        zk.createNode(Node(s"{path.name}/parent/c"), "baz".getBytes, PERSISTENT)
        println("child map: %s".format(childMap)) // NOTE: real code should synchronize access on childMap

        1 === 1
      }
    }
  }
}
