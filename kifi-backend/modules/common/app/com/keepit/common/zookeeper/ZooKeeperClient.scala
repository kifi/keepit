package com.keepit.common.zookeeper

import com.google.inject.{Inject, Singleton, ImplementedBy}
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
import scala.collection.JavaConversions._
import scala.collection.mutable.{ArrayBuffer, SynchronizedBuffer}
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext


object Node {
  def apply(path: String): Node = {
    if (!path.trim.startsWith("/")) throw new Exception(s"not an absolute path: $path")
    val components = (path split "/").filter{ _.trim.length > 0 }
    if (components.length == 0) throw new Exception(s"no node name found")
    new Node(components.mkString("/", "/", ""), components(components.length - 1))
  }

  def apply(parent: Node, name: String): Node = {
    if (isSimpleName(name)) throw new Exception(s"not a simple name: $name")
    new Node(parent.path + "/" + name, name)
  }

  private def isSimpleName(name: String): Boolean =  {
    name.indexOf('/') >= 0
  }
}

class Node(val path: String, val name: String) {

  override def equals(other: Any): Boolean = {
    other match {
      case otherNode: Node => path == otherNode.path
      case _ => false
    }
  }
  override def hashCode: Int = path.hashCode
  override def toString(): String = path
  def canEqual(other: Any): Boolean = other.isInstanceOf[Node]

  def parent: Option[Node] = {
    val prefix = path.substring(0, path.lastIndexOf("/"))
    if (prefix.length == 0) None else Some(Node(prefix))
  }

  // all ancestor nodes in the path of the given node (excluding itself)
  def ancestors(): Seq[Node] = {
    parent match {
      case Some(parentNode) =>
        val components = (parentNode.path split "/").filter{ _.trim.length > 0 }
        (1 to components.length).map{ i =>
          new Node(components.slice(0, i).mkString("/", "/", "") , components(i - 1))
        }
      case None => Seq()
    }
  }
}

trait ZooKeeperClient {
  def onConnected(handler: ZooKeeperSession => Unit): Unit
  def session[T](f: ZooKeeperSession => T): T
  def close(): Unit
}

trait ZooKeeperSession {
  def getState(): ZooKeeper.States

  def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit): Unit
  def watchChildren(node: Node, updateChildren : Seq[Node] => Unit): Unit
  def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T): Unit
  def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit): Unit

  def create(node: Node): Node
  def create(node: Node, data: Array[Byte]): Node
  def createChild(parent: Node, name: String, data: Array[Byte], createMode: CreateMode): Node

  def getChildren(node: Node): Seq[Node]

  def getData(node: Node): Array[Byte]
  def getDataOpt(node: Node): Option[Array[Byte]]

  def setData(node: Node, data: Array[Byte]): Unit

  def delete(node: Node): Unit
  def deleteRecursive(node: Node): Unit
}

/**
 * The code was originally taken from https://github.com/twitter/scala-zookeeper-client/blob/master/src/main/scala/com/twitter/zookeeper/ZooKeeperClient.scala
 * It was abandoned by twitter in favor of https://github.com/twitter/util/tree/master/util-zk
 */
class ZooKeeperClientImpl(servers: String, sessionTimeout: Int,
                      watcher: Option[ZooKeeperClient => Unit]) extends ZooKeeperClient with Logging {

  private[this] val onConnectedHandlers = new ArrayBuffer[ZooKeeperSession=>Unit]

  private def connect(): Future[ZooKeeperSessionImpl] = {
    val promise = Promise[Unit]

    val sessionWatcher = new Watcher {
      def process(event : WatchedEvent) { sessionEvent(promise, event) }
    }

    log.info(s"Attempting to connect to zookeeper servers $servers")
    val zk = new ZooKeeperSessionImpl(new ZooKeeper(servers, sessionTimeout, sessionWatcher))

    promise.future.map{ _ =>
      onConnectedHandlers.synchronized{ onConnectedHandlers.foreach(handler => execOnConnectedHandler(zk, handler)) }
      zk
    }
  }

  @volatile private[this] var zkSession : ZooKeeperSessionImpl = Await.result(connect(), Duration.Inf)

  def onConnected(handler: ZooKeeperSession=>Unit): Unit = onConnectedHandlers.synchronized {
    onConnectedHandlers += handler
    val zk = zkSession
    if (zk.getState() == ZooKeeper.States.CONNECTED) execOnConnectedHandler(zk, handler) // if already connected, execute the handler immediately
  }

  private def execOnConnectedHandler(zk: ZooKeeperSession, handler: ZooKeeperSession => Unit): Unit = {
    try {
      handler(zk)
    } catch {
    case e:Exception =>
      log.error("Exception during execution of an onConnected handler", e)
    }
  }

  private def sessionEvent(promise : Promise[Unit], event : WatchedEvent) {
    log.info("ZooKeeper event: %s".format(event))
    event.getState match {
      case KeeperState.SyncConnected => {
        try {
          watcher.map(fn => fn(this))
        } catch {
          case e:Exception =>
            log.error("Exception during zookeeper connection established callback", e)
        }
        promise.success() // invoke onConnected handlers
      }
      case KeeperState.Expired => {
        // Session was expired; establish a new zookeeper connection and save a session
        future{
          zkSession = Await.result(connect(), Duration.Inf)
        }
      }
      case KeeperState.AuthFailed => {
        promise.failure(new KeeperException.AuthFailedException())
      }
      case _ => // Disconnected -- zookeeper library will handle reconnects
    }
  }

  def session[T](f: ZooKeeperSession => T): T = f(zkSession)

  def close(): Unit = zkSession.close()
}

class ZooKeeperSessionImpl(zk : ZooKeeper) extends ZooKeeperSession with Logging {

  private implicit def nodeToPath(node: Node): String = node.path

  private def getChildren(node: Node, watch: Boolean): Seq[Node] = {
    val childNames = zk.getChildren(node, watch)
    childNames.map{ Node(node, _) }
  }

  private def getChildren(node: Node, watcher: Watcher): Seq[Node] = {
    val childNames = zk.getChildren(node, watcher)
    childNames.map{ Node(node, _) }
  }

  def getChildren(node: Node): Seq[Node] = getChildren(node, false)

  def close(): Unit = zk.close()
  def getState(): ZooKeeper.States = zk.getState()

  def create(node: Node, data: Array[Byte]): Node = {
    Node(zk.create(node, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT))
  }

  def createChild(parent: Node, name: String, data: Array[Byte], createMode: CreateMode): Node = {
    Node(zk.create(Node(parent, name), data, Ids.OPEN_ACL_UNSAFE, createMode))
  }

  /**
   * ZooKeeper version of mkdir -p
   */
  def create(node: Node): Node = {
    for (ancestor <- node.ancestors()) {
      try {
        log.debug(s"Creating path in create: ancestor.path")
        zk.create(ancestor.path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      } catch {
        case _: KeeperException.NodeExistsException => // ignore existing nodes
      }
    }
    try {
      log.debug(s"Creating path in create: $node")
      create(node, null)
    } catch {
      case _: KeeperException.NodeExistsException => node // ignore existing nodes
    }
  }

  def getData(node: Node): Array[Byte] = zk.getData(node, false, null)

  def getDataOpt(node: Node): Option[Array[Byte]] = try{
      Some(getData(node))
    }
    catch {
      case e: KeeperException.NoNodeException => None
    }

  def setData(node: Node, data: Array[Byte]): Unit = zk.setData(node, data, -1)

  def delete(node: Node): Unit = zk.delete(node, -1)

  /**
   * Delete a node along with all of its children
   */
  def deleteRecursive(node: Node) {
    val children = getChildren(node)
    for (child <- children) {
      deleteRecursive(child)
    }
    delete(node)
  }

  /**
   * Watches a node. When the node's data is changed, onDataChanged will be called with the
   * new data value as a byte array. If the node is deleted, onDataChanged will be called with
   * None and will track the node's re-creation with an existence watch.
   */
  def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit) {
    log.debug(s"Watching node $node")
    def updateData {
      try {
        onDataChanged(Some(zk.getData(node, dataGetter, null)))
      } catch {
        case e:KeeperException => {
          log.warn(s"Failed to read node ${node}", e)
          deletedData
        }
      }
    }

    def deletedData {
      onDataChanged(None)
      if (zk.exists(node, dataGetter) != null) {
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
   * Gets the children for a node, watches
   * for each NodeChildrenChanged event and runs the supplied updateChildren function and
   * re-watches the node's children.
   */
  def watchChildren(node: Node, updateChildren: Seq[Node] => Unit){
    val watchedChildren = scala.collection.mutable.HashSet[Node]()

    case class ChildWatcher(child: Node) extends Watcher {
      def process(event: WatchedEvent) : Unit = watchedChildren.synchronized{
        watchedChildren -= child
        val children = getChildren(node, false)
        updateChildren(children)
        doWatchChildren(children)
      }
    }

    class ParentWatcher() extends Watcher {
      def process(event: WatchedEvent): Unit = {
        //in case deleted, watch for recreation
        if (event.getType==EventType.NodeDeleted){
          updateChildren(List())
          zk.exists(node, new ParentWatcher())
        } else { //otherwise, recreate watch on self and on new children
          val children = getChildren(node, new ParentWatcher())
          updateChildren(children)
          doWatchChildren(children)
        }
      }
    }

    def doWatchChildren(children: Seq[Node]) : Unit = watchedChildren.synchronized {
      children.filterNot(watchedChildren.contains _).foreach{ child =>
        try {
          zk.getData(child.path, ChildWatcher(node), new Stat())
          watchedChildren += child
        } catch {
          case e:KeeperException =>
            log.warn("Failed to place watch on a child node!")
        }
      }
    }

    //check immediately
    try{
      val children = getChildren(node, new ParentWatcher())
      updateChildren(children)
      doWatchChildren(children)
    } catch {
      case e :KeeperException => zk.exists(node, new ParentWatcher())
    }
  }

  /**
   * WARNING: watchMap must be thread-safe. Writing is synchronized on the watchMap. Readers MUST
   * also synchronize on the watchMap for safety.
   */
  def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T): Unit =
    watchChildrenWithData(node, watchMap, deserialize, None)

  /**
   * Watch a set of nodes with an explicit notifier. The notifier will be called whenever
   * the watchMap is modified
   */
  def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit): Unit =
    watchChildrenWithData(node, watchMap, deserialize, Some(notifier))

  private def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T],
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
          log.debug(s"Child ${child.path} removed")
          watchMap -= child
        }
        // add new children to the watch map
        for (child <- addedChildren) {
          // node is added via nodeChanged callback
          log.debug(s"Child ${child.path} added")
          watchNode(child, nodeChanged(child))
        }
      }
      for (child <- removedChildren) {
        notifier.map(f => f(child))
      }
    }

    watchChildren(node, parentWatcher)
  }
}
