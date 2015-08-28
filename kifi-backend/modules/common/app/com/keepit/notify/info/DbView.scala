package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.model._

/**
 * Represents a view, or subset, of the shoebox database, mostly from the perspective of Eliza.
 *
 * The implementation uses a map from keys to maps from ids to results. While the underlying maps are
 * untyped, types are enforced through the public facing methods [[update]] and [[lookup]].
 */
class DbView(private val keyMap: Map[DbViewKey[_, _], Map[Id[_], Any]]) {

  def update[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M], result: R): DbView = {
    val objMap = keyMap.getOrElse(key, Map.empty)
    val assocObjMap = objMap + (id -> result)
    new DbView(keyMap + (key -> assocObjMap))
  }

  def lookup[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M]): R =
    keyMap(key)(id).asInstanceOf[R]

  def contains[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M]): Boolean =
    keyMap.get(key).flatMap(_.get(id)).isDefined

}

object DbView {

  /**
   * Constructs an empty DbView.
   *
   * This is the only way to construct a DbView from the outside.
   */
  def apply() = new DbView(Map.empty)

}

/**
 * Represents a key which generates DB view requests.
 */
class DbViewKey[M <: HasId[M], R] {

  /**
   * Builds a request using the given model.
   */
  def apply(id: Id[M]): DbViewRequest[M, R] = DbViewRequest(this, id)

}

/**
 * Contains a listing of Db view keys which can be used to lookup models.
 */
trait DbViewKeyList {

  val user = DbViewKey[User, UserNotificationInfo]
  val library = DbViewKey[Library, LibraryNotificationInfo]
  val keep = DbViewKey[Keep, Keep]
  val organization = DbViewKey[Organization, OrganizationNotificationInfo]

}

object DbViewKey extends DbViewKeyList {

  def apply[M <: HasId[M], R] = new DbViewKey[M, R]

}

/**
 * Represents a request for a certain model in a db view.
 *
 * @param key The key of the request
 * @param id The id of the model to lookup
 * @tparam M The type of model to lookup
 * @tparam R The type of result of the request
 */
case class DbViewRequest[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M]) {

  def lookup(view: DbView): R = view.lookup(key, id)

  def contained(view: DbView): Boolean = view.contains(key, id)

}

/**
 * Represents a wrapper of a function that requests a whole bunch of items from a potential db view, then
 * constructs a value.
 */
case class UsingDbView[A](requests: Seq[ExDbViewRequest])(val fn: DbView => A)

/**
 * The compiler cannot infer that a Seq of specific db view requests should be existential using the Seq(...) method,
 * so this hints it explicitly.
 */
object Requests {
  def apply(requests: ExDbViewRequest*): Seq[ExDbViewRequest]
    = requests
}
