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
import java.util.concurrent.atomic.AtomicReference


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
  def watchChildren(node: Node, updateChildren : Seq[Node] => Unit, watchData: Boolean = true): Unit
  def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T): Unit
  def watchChildrenWithData[T](node: Node, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit): Unit

  def create(node: Node): Node
  def createChild(parent: Node, childName: String, createMode: CreateMode = CreateMode.PERSISTENT): Node
  def createChild[T](parent: Node, childName: String, data: T, createMode: CreateMode = CreateMode.PERSISTENT)(implicit serializer: T => Array[Byte]): Node

  def get(node: Node): Option[Node]
  def getChildren(node: Node): Seq[Node]

  def getData[T](node: Node)(implicit deserializer: Array[Byte] => T): Option[T]
  def setData[T](node: Node, data: T)(implicit serializer: T => Array[Byte]): Unit
  def deleteData(node: Node): Unit

  def delete(node: Node): Unit
  def deleteRecursive(node: Node): Unit
}

/**
 * The code was originally taken from https://github.com/twitter/scala-zookeeper-client/blob/master/src/main/scala/com/twitter/zookeeper/ZooKeeperClient.scala
 * It was abandoned by twitter in favor of https://github.com/twitter/util/tree/master/util-zk
 */
class ZooKeeperClientImpl(val servers: String, val sessionTimeout: Int,
                      watcher: Option[ZooKeeperClient => Unit]) extends ZooKeeperClient with Logging {

  private[this] val zkSession : AtomicReference[ZooKeeperSessionImpl] = new AtomicReference(null)
  private[this] val onConnectedHandlers = new ArrayBuffer[ZooKeeperSession=>Unit]

  private def connect(): Future[ZooKeeperSessionImpl] = {
    val promise = Promise[Unit]

    log.info(s"Attempting to connect to zookeeper servers $servers")
    val zk = new ZooKeeperSessionImpl(this, promise)

    promise.future.map{ _ =>
      onConnectedHandlers.synchronized{ onConnectedHandlers.foreach(handler => zk.execOnConnectHandler(handler)) }
      zk
    }
  }

  def onConnected(handler: ZooKeeperSession=>Unit): Unit = onConnectedHandlers.synchronized {
    onConnectedHandlers += handler
    val zk = zkSession.get
    zk.execOnConnectHandler(handler) // if already connected, this executes the handler immediately
  }

  def refreshSession(): Unit = future{
    val old = zkSession.getAndSet(Await.result(connect(), Duration.Inf))
    if (old != null) try { old.close() } catch { case _: Throwable => } // make sure the old session is closed
  }

  def processSessionEvent(event: WatchedEvent) {
    log.info("ZooKeeper event: %s".format(event))
    event.getState match {
      case KeeperState.SyncConnected => {
        try {
          watcher.map(fn => fn(this))
        } catch {
          case e:Exception =>
            log.error("Exception during zookeeper connection established callback", e)
        }
      }
      case KeeperState.Expired => {
        // Session was expired, reestablish a new zookeeper connection
        refreshSession()
      }
      case _ => // Disconnected or something else
    }
  }

  // establish a zk session
  zkSession.compareAndSet(null, Await.result(connect(), Duration.Inf))

  def session[T](f: ZooKeeperSession => T): T = f(zkSession.get)

  def close(): Unit = zkSession.get.close()
}

class ZooKeeperSessionImpl(zkClient: ZooKeeperClientImpl, promise: Promise[Unit]) extends ZooKeeperSession with Logging {

  class SessionWatcher extends Watcher {
    def process(event : WatchedEvent) {
      zkClient.processSessionEvent(event) // let zkClient handle the session expiration
      event.getState match {
        case KeeperState.SyncConnected => {
          // invoke onConnected handlers by completing the promise
          val isNewConnection = promise.trySuccess()
          // if not a new connection, we must have recovered from connection loss
          if (!isNewConnection) ZooKeeperSessionImpl.this.recover()
        }
        case KeeperState.AuthFailed => {
          promise.tryFailure(new KeeperException.AuthFailedException())
        }
        case _ => // Disconnected, Expired or something else
      }
    }
  }

  private[this] val zk = new ZooKeeper(zkClient.servers, zkClient.sessionTimeout, new SessionWatcher)

  private implicit def nodeToPath(node: Node): String = node.path

  // watch management
  sealed trait RegisteredWatch
  case class RegisteredNodeWatch(node: Node, onDataChanged : Option[Array[Byte]] => Unit) extends RegisteredWatch
  case class RegisteredChildWatch(node: Node, updateChildren: Seq[Node] => Unit, watchData: Boolean) extends RegisteredWatch
  private[this] val watchList = new ArrayBuffer[RegisteredWatch]
  private def registerWatch(watch: RegisteredWatch): Unit = watchList.synchronized { watchList += watch }

  // onConnectedHandler management
  private[this] val pendingHandlerList = new ArrayBuffer[ZooKeeperSession => Unit]

  // recover from connection loss
  def recover() = {
    // execute pending onConnected handlers if any
    val pending = new ArrayBuffer[ZooKeeperSession => Unit]
    pendingHandlerList.synchronized {
      pending ++= pendingHandlerList
      pendingHandlerList.clear()
    }
    pending.foreach(execOnConnectHandler)

    // register watches again
    log.info("recovering watches")
    watchList.synchronized {
      watchList.foreach{ registeredWatch =>
        registeredWatch match {
          case RegisteredNodeWatch(node, onDataChanged) => watchNode(node, onDataChanged)
          case RegisteredChildWatch(node, updateChildren, watchData) => watchChildren(node, updateChildren, watchData)
        }
      }
    }
  }

  def execOnConnectHandler(handler: ZooKeeperSession => Unit): Unit = {
    pendingHandlerList.synchronized {
      if (zk.getState() == ZooKeeper.States.CONNECTED) {
        try { handler(this) } catch {
          case e: Throwable => log.error("Exception during execution of an onConnected handler", e)
        }
      } else {
        pendingHandlerList.synchronized{ pendingHandlerList += handler }
      }
    }
  }

  def getChildren(node: Node): Seq[Node] = {
    val childNames = zk.getChildren(node, false)
    childNames.map{ Node(node, _) }
  }

  private def getChildren(node: Node, watcher: Watcher): Seq[Node] = {
    val childNames = zk.getChildren(node, watcher)
    childNames.map{ Node(node, _) }
  }

  def close(): Unit = zk.close()
  def getState(): ZooKeeper.States = zk.getState()

  // ZooKeeper version of mkdir -p
  def create(node: Node): Node = {
    for (ancestor <- node.ancestors()) {
      try {
        log.debug(s"Creating node in create: ${ancestor.path}")
        zk.create(ancestor.path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      } catch {
        case _: KeeperException.NodeExistsException => // ignore existing nodes
      }
    }
    try {
      log.debug(s"Creating node in create: $node")
      zk.create(node.path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      node
    } catch {
      case _: KeeperException.NodeExistsException => node // ignore existing nodes
    }
  }

  def createChild(parent: Node, childName: String, createMode: CreateMode = CreateMode.PERSISTENT): Node = {
    Node(zk.create(Node(parent, childName), null, Ids.OPEN_ACL_UNSAFE, createMode))
  }

  def createChild[T](parent: Node, childName: String, data: T, createMode: CreateMode = CreateMode.PERSISTENT)(implicit serializer: T => Array[Byte]): Node = {
    Node(zk.create(Node(parent, childName), serializer(data), Ids.OPEN_ACL_UNSAFE, createMode))
  }

  def get(node: Node): Option[Node] = {
    if (zk.exists(node, null) != null) Some(node) else None
  }

  def getData[T](node: Node)(implicit deserializer: Array[Byte] => T): Option[T] = {
    Option(zk.getData(node, false, null)).map(deserializer)
  }

  def setData[T](node: Node, data: T)(implicit serializer: T => Array[Byte]): Unit = {
    zk.setData(node, serializer(data), -1)
  }

  def deleteData(node: Node): Unit = {
    zk.setData(node, null, -1)
  }

  def delete(node: Node): Unit = zk.delete(node, -1)

  // Delete a node along with all of its descendants
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
          log.warn(s"Failed to read node ${node.path}", e)
          deletedData
        }
      }
    }

    def deletedData {
      onDataChanged(None)
      if (zk.exists(node, dataGetter) != null) {
        // Node was re-created by the time we called zk.exists
        updateData
      }
    }

    def reregister {
      zk.getData(node, dataGetter, null)
    }

    def dataGetter = new Watcher {
      def process(event : WatchedEvent) {
        event.getType match {
          case EventType.NodeDataChanged | EventType.NodeCreated => updateData
          case EventType.NodeDeleted => deletedData
          case EventType.NodeChildrenChanged => reregister
          case _ => log.info(s"session event, losing a watch on ${node.path}") // session event, we intentionally lose this watch
        }
      }
    }

    registerWatch(RegisteredNodeWatch(node, onDataChanged))
    updateData
  }

  /**
   * Gets the children for a node, watches
   * for each NodeChildrenChanged event and runs the supplied updateChildren function and
   * re-watches the node's children.
   */
  def watchChildren(node: Node, updateChildren: Seq[Node] => Unit, watchData: Boolean = true){
    val watchedChildren = scala.collection.mutable.HashSet[Node]()

    class ChildWatcher(child: Node) extends Watcher {
      def process(event: WatchedEvent) : Unit = watchedChildren.synchronized {
        watchedChildren -= child
        event.getType match {
          case EventType.NodeDataChanged | EventType.NodeCreated =>
            val children = getChildren(node)
            updateChildren(children)
            doWatchChildren(children)
          case EventType.NodeChildrenChanged =>
            val children = getChildren(node)
            doWatchChildren(children)
          case EventType.NodeDeleted => // deletion handled via parent watch
          case _ => log.info(s"session event, losing a ChildWatcher on ${child.path}") // session event, we intentionally lose this watch
        }
      }
    }

    class ParentWatcher() extends Watcher {
      def process(event: WatchedEvent): Unit = {
        //in case deleted, watch for recreation
        event.getType match {
          case EventType.NodeDataChanged | EventType.NodeCreated =>
            val children = getChildren(node, new ParentWatcher())
            doWatchChildren(children)
          case EventType.NodeChildrenChanged =>
            val children = getChildren(node, new ParentWatcher())
            updateChildren(children)
            doWatchChildren(children)
          case EventType.NodeDeleted =>
            // the node may be re-created by the time we called zk.exists
            val children = if (zk.exists(node, new ParentWatcher()) == null) List() else getChildren(node, new ParentWatcher())
            updateChildren(children)
            doWatchChildren(children)
          case _ => log.info(s"session event, losing a ParentWatcher on ${node.path}") // session event, we intentionally lose this watch
        }
      }
    }

    def doWatchChildren(children: Seq[Node]) : Unit = {
      if (watchData) {
        watchedChildren.synchronized {
          children.filterNot(watchedChildren.contains _).foreach{ child =>
            try {
              zk.getData(child.path, new ChildWatcher(child), new Stat())
              watchedChildren += child
            } catch {
              case e: KeeperException => log.warn(s"Failed to place watch on a child node!: ${child.path}", e)
            }
          }
        }
      }
    }

    //check immediately
    registerWatch(RegisteredChildWatch(node, updateChildren, watchData))
    val children = try{
      getChildren(node, new ParentWatcher())
    } catch {
      case e: KeeperException.NoNodeException =>
        // the node may be re-created by the time we called zk.exists
        if (zk.exists(node, new ParentWatcher()) == null) List() else getChildren(node, new ParentWatcher())
    }
    updateChildren(children)
    doWatchChildren(children)
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
