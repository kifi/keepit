package com.keepit.common.zookeeper

import java.util.{List => JList}
import com.keepit.common.logging.Logging
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.immutable.Set
import org.apache.zookeeper.{CreateMode, KeeperException, Watcher, WatchedEvent, ZooKeeper}
import org.apache.zookeeper.data.{ACL, Stat, Id}
import org.apache.zookeeper.ZooDefs.Ids
import org.apache.zookeeper.Watcher.Event.EventType
import org.apache.zookeeper.Watcher.Event.KeeperState
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

case class Path(name: String) extends AnyVal {
  override def toString = name
  def asNode = Node(name)
}

case class Node(name: String) extends AnyVal {
  override def toString = name
  def asPath = Path(name)
}

/**
 * The code was originally taken from https://github.com/twitter/scala-zookeeper-client/blob/master/src/main/scala/com/twitter/zookeeper/ZooKeeperClient.scala
 * It was abandoned by twitter in favor of https://github.com/twitter/util/tree/master/util-zk
 */
class ZooKeeperClient(servers: String, sessionTimeout: Int, basePath : Path,
                      watcher: Option[ZooKeeperClient => Unit]) extends Logging {
  @volatile private var zk : ZooKeeper = null
  connect()

  def getHandle() : ZooKeeper = zk

  private implicit def nodes(strings: JList[String]): Seq[Node] =
    asScalaBuffer(strings) map {n => Node(n)}

  private implicit def paths(strings: JList[String]): Seq[Path] =
    asScalaBuffer(strings) map {p => Path(p)}

  /**
   * connect() attaches to the remote zookeeper and sets an instance variable.
   */
  private def connect() {
    val connectionLatch = new CountDownLatch(1)
    val assignLatch = new CountDownLatch(1)
    if (zk != null) {
      zk.close()
      zk = null
    }
    zk = new ZooKeeper(servers, sessionTimeout,
                       new Watcher { def process(event : WatchedEvent) {
                         sessionEvent(assignLatch, connectionLatch, event)
                       }})
    assignLatch.countDown()
    log.info(s"Attempting to connect to zookeeper servers $servers")
    connectionLatch.await()
  }

  def sessionEvent(assignLatch: CountDownLatch, connectionLatch : CountDownLatch, event : WatchedEvent) {
    log.info("Zookeeper event: %s".format(event))
    assignLatch.await()
    event.getState match {
      case KeeperState.SyncConnected => {
        try {
          watcher.map(fn => fn(this))
        } catch {
          case e:Exception =>
            log.error("Exception during zookeeper connection established callback", e)
        }
        connectionLatch.countDown()
      }
      case KeeperState.Expired => {
        // Session was expired; create a new zookeeper connection
        connect()
      }
      case _ => // Disconnected -- zookeeper library will handle reconnects
    }
  }

  /**
   * Given a string representing a path, return each subpath
   * Ex. subPaths("/a/b/c", "/") == ["/a", "/a/b", "/a/b/c"]
   */
  def subPaths(path: Path, sep: Char) = {
    val l = path.name.split(sep).toList
    val paths = l.tail.foldLeft[List[Path]](Nil){(xs, x) =>
      (Path(xs.headOption.getOrElse("") + sep.toString + x))::xs}
    paths.reverse
  }

  private def makeNodePath(path: Path) = Node("%s/%s".format(basePath, path.name).replaceAll("//", "/"))

  def getChildren(path: Path): Seq[Node] = {
    zk.getChildren(makeNodePath(path).name, false)
  }

  def close() = zk.close

  def isAlive: Boolean = {
    // If you can get the root, then we're alive.
    val result: Stat = zk.exists("/", false) // do not watch
    result.getVersion >= 0
  }

  def create(path: Path, data: Array[Byte], createMode: CreateMode): Path = {
    Path(zk.create(makeNodePath(path).name, data, Ids.OPEN_ACL_UNSAFE, createMode))
  }

  def createNode(node: Node, data: Array[Byte], createMode: CreateMode): Node =
    create(node.asPath, data, createMode).asNode

  /**
   * ZooKeeper version of mkdir -p
   */
  def createPath(path: Path) {
    for (path <- subPaths(makeNodePath(path).asPath, '/')) {
      try {
        log.debug(s"Creating path in createPath: $path")
        zk.create(path.name, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      } catch {
        case _: KeeperException.NodeExistsException => {} // ignore existing nodes
      }
    }
  }

  def get(node: Node): Array[Byte] = {
    zk.getData(makeNodePath(node.asPath).name, false, null)
  }

  def set(node: Node, data: Array[Byte]) {
    zk.setData(makeNodePath(node.asPath).name, data, -1)
  }

  def delete(path: Path): Unit = {
    zk.delete(makeNodePath(path).name, -1)
  }

  def deleteNode(node: Node): Unit = delete(node.asPath)

  /**
   * Delete a node along with all of its children
   */
  def deleteRecursive(path: Path) {
    val children = getChildren(path)
    for (node <- children) {
      deleteRecursive(Path(path.name + '/' + node.name))
    }
    delete(path)
  }

  /**
   * Watches a node. When the node's data is changed, onDataChanged will be called with the
   * new data value as a byte array. If the node is deleted, onDataChanged will be called with
   * None and will track the node's re-creation with an existence watch.
   */
  def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit) {
    log.debug(s"Watching node $node")
    val path = makeNodePath(node.asPath)
    def updateData {
      try {
        onDataChanged(Some(zk.getData(path.name, dataGetter, null)))
      } catch {
        case e:KeeperException => {
          log.warn(s"Failed to read node $path", e)
          deletedData
        }
      }
    }

    def deletedData {
      onDataChanged(None)
      if (zk.exists(path.name, dataGetter) != null) {
        // Node was re-created by the time we called zk.exist
        updateData
      }
    }
    def dataGetter = new Watcher {
      def process(event : WatchedEvent) {
        if (event.getType == EventType.NodeDataChanged || event.getType == EventType.NodeCreated) {
          updateData
        } else if (event.getType == EventType.NodeDeleted) {
          deletedData
        }
      }
    }
    updateData
  }

  /**
   * Gets the children for a node (relative path from our basePath), watches
   * for each NodeChildrenChanged event and runs the supplied updateChildren function and
   * re-watches the node's children.
   */
  def watchChildren(path: Path, updateChildren : Seq[Node] => Unit) {
    val node = makeNodePath(path)
    val childWatcher = new Watcher {
      def process(event : WatchedEvent) {
        if (event.getType == EventType.NodeChildrenChanged ||
            event.getType == EventType.NodeCreated) {
          watchChildren(node.asPath, updateChildren)
        }
      }
    }
    try {
      val children = zk.getChildren(path.name, childWatcher)
      updateChildren(children)
    } catch {
      case e:KeeperException => {
        // Node was deleted -- fire a watch on node re-creation
        log.warn(s"Failed to read node $path", e)
        updateChildren(List())
        zk.exists(path.name, childWatcher)
      }
    }
  }

  /**
   * WARNING: watchMap must be thread-safe. Writing is synchronized on the watchMap. Readers MUST
   * also synchronize on the watchMap for safety.
   */
  def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T) {
    watchChildrenWithData(path, watchMap, deserialize, None)
  }

  /**
   * Watch a set of nodes with an explicit notifier. The notifier will be called whenever
   * the watchMap is modified
   */
  def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T],
                               deserialize: Array[Byte] => T, notifier: Node => Unit) {
    watchChildrenWithData(path, watchMap, deserialize, Some(notifier))
  }

  private def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T],
                                       deserialize: Array[Byte] => T, notifier: Option[Node => Unit]) {
    def nodeChanged(child : Node)(childData : Option[Array[Byte]]) {
      childData match {
        case Some(data) => {
          watchMap.synchronized {
            watchMap(child) = deserialize(data)
          }
          notifier.map(f => f(child))
        }
        case None => // deletion handled via parent watch
      }
    }

    def parentWatcher(children : Seq[Node]) {
      val childrenSet = Set(children : _*)
      val watchedKeys = Set(watchMap.keySet.toSeq : _*)
      val removedChildren = watchedKeys -- childrenSet
      val addedChildren = childrenSet -- watchedKeys
      watchMap.synchronized {
        // remove deleted children from the watch map
        for (child <- removedChildren) {
          log.debug(s"Path $path: child $child removed")
          watchMap -= child
        }
        // add new children to the watch map
        for (child <- addedChildren) {
          // node is added via nodeChanged callback
          log.debug(s"Path $path: child $child added")
          watchNode(Node("%s/%s".format(path.name, child.name)), nodeChanged(child))
        }
      }
      for (child <- removedChildren) {
        notifier.map(f => f(child))
      }
    }

    watchChildren(path, parentWatcher)
  }
}
